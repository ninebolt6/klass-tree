package io.github.ninebolt6.klasstree.analyzer

import io.github.ninebolt6.klasstree.graph.AnalysisSpec
import io.github.ninebolt6.klasstree.graph.DependencyGraph
import io.github.ninebolt6.klasstree.graph.EdgeKind
import io.github.ninebolt6.klasstree.graph.GraphEdge
import io.github.ninebolt6.klasstree.graph.ModuleSpec
import io.github.ninebolt6.klasstree.graph.NodeKind
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnalyzerJavaTest {
    @Test
    fun `java sources produce nodes and edges in both directions`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Helper.java" to """
                    package com.example;

                    public interface Helper {
                        void help();
                    }
                """,
                    "RealHelper.java" to """
                    package com.example;

                    public class RealHelper implements Helper {
                        @Override
                        public void help() {
                            UserKt.topLevel();
                        }
                    }
                """,
                    "User.kt" to """
                    package com.example

                    fun topLevel() {}

                    class User(private val helper: RealHelper) {
                        fun go() {
                            RealHelper().help()
                            helper.help()
                        }
                    }
                """,
                ),
            )

        val names =
            result.graph.nodes
                .filter { it.kind != NodeKind.FILE_SCOPE }
                .map { it.fqName to it.kind }
                .toSet()
        assertTrue(names.contains("com.example.Helper" to NodeKind.INTERFACE))
        assertTrue(names.contains("com.example.RealHelper" to NodeKind.CLASS))
        assertNotNull(result.graph.nodes.singleOrNull { it.kind == NodeKind.FILE_SCOPE })

        assertNotNull(edge(result.graph, "com.example.RealHelper", "com.example.Helper", EdgeKind.INHERITANCE))
        assertNotNull(edge(result.graph, "com.example.RealHelper", "com.example", EdgeKind.FUNCTION_CALL))
        assertNotNull(edge(result.graph, "com.example.User", "com.example.RealHelper", EdgeKind.TYPE_REFERENCE))
        assertNotNull(edge(result.graph, "com.example.User", "com.example.RealHelper", EdgeKind.CONSTRUCTOR_CALL))
        assertNotNull(edge(result.graph, "com.example.User", "com.example.RealHelper", EdgeKind.FUNCTION_CALL))
        assertEquals(0, result.stats.resolution.unresolved)
    }

    @Test
    fun `java sources are skipped when includeJavaSources is false`() {
        val result =
            analyze(
                module(":app:main", "J.java" to "package com.example; class J {}"),
                includeJavaSources = false,
            )
        assertTrue(result.graph.nodes.none { it.fqName == "com.example.J" })
    }

    @Test
    fun `java anonymous class construction still reaches the named supertype`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Task.java" to "package com.example; public interface Task { void run(); }",
                    "Main.java" to """
                    package com.example;
                    public class Main {
                        Runnable t = new Task() { public void run() {} };
                    }
                """,
                ),
            )
        assertNotNull(
            edge(result.graph, "com.example.Main", "com.example.Task", EdgeKind.CONSTRUCTOR_CALL),
            "anonymous 'new Task(){}' must keep the reference to Task",
        )
    }

    @Test
    fun `java throws static-import field and enum edges`() {
        val result =
            analyze(
                module(
                    ":lib:main",
                    "E.java" to "package lib; public enum E { A, B }",
                    "Boom.java" to "package lib; public class Boom extends Exception {}",
                    "Const.java" to "package lib; public class Const { public static final int N = 1; }",
                ),
                module(
                    ":app:main",
                    "Main.java" to """
                    package app;
                    import static lib.Const.N;
                    import lib.Boom;
                    import lib.E;
                    public class Main {
                        int f = N;
                        E e = E.A;
                        void go() throws Boom { throw new Boom(); }
                    }
                """,
                    dependencies = listOf(":lib:main"),
                ),
            )
        assertNotNull(edge(result.graph, "app.Main", "lib.Boom", EdgeKind.TYPE_REFERENCE), "throws clause")
        assertNotNull(edge(result.graph, "app.Main", "lib.Boom", EdgeKind.CONSTRUCTOR_CALL), "throw new")
        assertNotNull(edge(result.graph, "app.Main", "lib.Const", EdgeKind.PROPERTY_ACCESS), "static import field")
        assertNotNull(edge(result.graph, "app.Main", "lib.E", EdgeKind.PROPERTY_ACCESS), "enum constant")
        assertEquals(
            NodeKind.ENUM,
            result.graph.nodes
                .single { it.fqName == "lib.E" }
                .kind,
        )
        assertEquals(
            NodeKind.CLASS,
            result.graph.nodes
                .single { it.fqName == "lib.Boom" }
                .kind,
        )
    }

    @Test
    fun `java nested classes and constructor chaining`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Outer.java" to """
                    package com.example;
                    public class Outer {
                        public static class Inner {}
                        Outer() { this(1); }
                        Outer(int i) {}
                    }
                    class Sub extends Outer.Inner {
                        Sub() { super(); }
                    }
                """,
                ),
            )
        assertNotNull(edge(result.graph, "com.example.Sub", "com.example.Outer.Inner", EdgeKind.INHERITANCE))
        assertNotNull(edge(result.graph, "com.example.Sub", "com.example.Outer.Inner", EdgeKind.CONSTRUCTOR_CALL))
        assertNotNull(
            result.graph.nodes.singleOrNull { it.fqName == "com.example.Outer.Inner" },
            "nested class must be indexed",
        )
    }

    @Test
    fun `java method references and lambdas`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Task.java" to "package com.example; public interface Task { void run(); }",
                    "Util.java" to "package com.example; public class Util { public static void work() {} }",
                    "Main.java" to """
                    package com.example;
                    public class Main {
                        Runnable r1 = Util::work;
                        Task t = () -> Util.work();
                    }
                """,
                ),
            )
        assertNotNull(
            edge(result.graph, "com.example.Main", "com.example.Util", EdgeKind.CALLABLE_REFERENCE),
            "method reference",
        )
        assertNotNull(
            edge(result.graph, "com.example.Main", "com.example.Task", EdgeKind.OPERATOR_OR_SYNTHETIC_CALL),
            "lambda must reference its functional interface",
        )
    }

    @Test
    fun `java class literal instanceof and generic arguments`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Foo.java" to "package com.example; public class Foo {}",
                    "Main.java" to """
                    package com.example;
                    import java.util.List;
                    public class Main {
                        Class<?> c = Foo.class;
                        boolean b = c instanceof Class;
                        List<Foo> l;
                    }
                """,
                ),
            )
        val typeRef = edge(result.graph, "com.example.Main", "com.example.Foo", EdgeKind.TYPE_REFERENCE)
        assertNotNull(typeRef)
        assertTrue(
            typeRef.callSites.size >= 2,
            "Foo.class and List<Foo> should produce multiple call sites, got " + typeRef.callSites,
        )
    }

    @Test
    fun `java references resolve across module boundaries`() {
        val result =
            analyze(
                module(":lib:main", "L.java" to "package lib; public class L { public static void go() {} }"),
                module(
                    ":app:main",
                    "A.java" to "package app; public class A { void m() { lib.L.go(); } }",
                    dependencies = listOf(":lib:main"),
                ),
            )
        assertNotNull(edge(result.graph, "app.A", "lib.L", EdgeKind.FUNCTION_CALL))
        assertEquals(0, result.stats.resolution.unresolved)
    }

    @Test
    fun `a broken java file does not affect other files`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Ok.java" to "package com.example; public class Ok {}",
                    "Broken.java" to "package com.example; public class {{{ not java at all",
                ),
            )
        assertTrue(
            result.stats.resolution.analysisErrors
                .isNotEmpty(),
        )
        assertNotNull(result.graph.nodes.singleOrNull { it.fqName == "com.example.Ok" })
    }

    @Test
    fun `package-info java and empty java file add no nodes`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "package-info.java" to "@Deprecated package com.example;",
                    "Empty.java" to "package com.example;",
                    "K.kt" to "package com.example\nclass K",
                ),
            )
        assertTrue(result.graph.nodes.none { it.sourceFile?.endsWith("package-info.java") == true })
        assertTrue(result.graph.nodes.none { it.sourceFile?.endsWith("Empty.java") == true })
        assertNotNull(result.graph.nodes.singleOrNull { it.fqName == "com.example.K" })
    }

    @Test
    fun `java imports alone produce no edges`() {
        val result =
            analyze(
                module(":lib:main", "L.java" to "package lib; public class L {}"),
                module(
                    ":app:main",
                    "A.java" to """
                    package app;
                    import lib.L;
                    import java.util.List;
                    import java.util.ArrayList;
                    public class A {}
                """,
                    dependencies = listOf(":lib:main"),
                ),
            )
        val a = result.graph.nodes.single { it.fqName == "app.A" }
        assertTrue(
            result.graph.edges.none { it.from == a.id },
            "unused imports must not create edges, got " + result.graph.edges,
        )
        assertEquals(0, result.stats.resolution.unresolved)
    }

    @Test
    fun `java generics varargs chained and enum-switch calls`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Util.java" to """
                    package com.example;
                    public class Util {
                        public static <T> T id(T t) { return t; }
                        public static String join(String... parts) { return ""; }
                        public static Util of() { return new Util(); }
                        public Util next() { return this; }
                    }
                """,
                    "Color.java" to "package com.example; public enum Color { R, G }",
                    "Main.java" to """
                    package com.example;
                    public class Main {
                        String s = Util.join("a", "b");
                        Main m = Util.id(this);
                        Util u = Util.of().next();
                        Color c = Color.R;
                        String kind(Color c) { switch (c) { case R: return "r"; default: return ""; } }
                    }
                """,
                ),
            )
        val calls =
            result.graph.edges.filter {
                it.from ==
                    result.graph.nodes
                        .single { n -> n.fqName == "com.example.Main" }
                        .id &&
                    it.to ==
                    result.graph.nodes
                        .single { n -> n.fqName == "com.example.Util" }
                        .id &&
                    it.kind == EdgeKind.FUNCTION_CALL
            }
        assertTrue(
            calls.isNotEmpty() && calls.sumOf { it.callSites.size } >= 3,
            "join/id/of/next should all resolve, got " + calls,
        )
        assertNotNull(
            edge(result.graph, "com.example.Main", "com.example.Color", EdgeKind.PROPERTY_ACCESS),
            "enum constant and switch case label",
        )
    }

    @Test
    fun `java annotation attribute class literal adds a type edge`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Ann.java" to "package com.example; public @interface Ann { Class<?> value(); }",
                    "Target.java" to "package com.example; public class Target {}",
                    "Use.java" to """
                    package com.example;
                    @Ann(Target.class)
                    public class Use {}
                """,
                ),
            )
        assertNotNull(edge(result.graph, "com.example.Use", "com.example.Ann", EdgeKind.ANNOTATION))
        assertEquals(
            NodeKind.ANNOTATION,
            result.graph.nodes
                .single { it.fqName == "com.example.Ann" }
                .kind,
        )
        assertNotNull(
            edge(result.graph, "com.example.Use", "com.example.Target", EdgeKind.TYPE_REFERENCE),
            "annotation argument type",
        )
    }

    @Test
    fun `java classes with equal simple names in different packages stay distinct`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "a/Dup.java" to "package a; public class Dup {}",
                    "b/Dup.java" to "package b; public class Dup {}",
                    "Use.java" to """
                    package use;
                    public class Use { a.Dup x; b.Dup y; }
                """,
                ),
            )
        assertNotNull(edge(result.graph, "use.Use", "a.Dup", EdgeKind.TYPE_REFERENCE))
        assertNotNull(edge(result.graph, "use.Use", "b.Dup", EdgeKind.TYPE_REFERENCE))
    }

    @Test
    fun `javadoc references do not produce edges`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Doc.java" to "package com.example; public class Doc {}",
                    "Use.java" to """
                    package com.example;
                    /** See {@link Doc} and {@link com.example.Doc#x}. */
                    public class Use {}
                """,
                ),
            )
        val use = result.graph.nodes.single { it.fqName == "com.example.Use" }
        assertTrue(
            result.graph.edges.none { it.from == use.id },
            "javadoc links must not create edges, got " + result.graph.edges,
        )
    }

    @Test
    fun `java static initializer super-method and anonymous-body calls`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Util.java" to
                        "package com.example; public class Util { public static void boot() {}" +
                        " public void m() {} }",
                    "Base.java" to "package com.example; public class Base { public void m() {} }",
                    "Sub.java" to """
                    package com.example;
                    public class Sub extends Base {
                        static { Util.boot(); }
                        public void m() { super.m(); }
                        Runnable r = new Runnable() { public void run() { Util.boot(); } };
                    }
                """,
                ),
            )
        val sub = result.graph.nodes.single { it.fqName == "com.example.Sub" }
        val util = result.graph.nodes.single { it.fqName == "com.example.Util" }
        val bootCalls =
            result.graph.edges.single {
                it.from == sub.id && it.to == util.id && it.kind == EdgeKind.FUNCTION_CALL
            }
        assertTrue(
            bootCalls.callSites.size >= 2,
            "static-init and anonymous-body calls should attribute to Sub, got " + bootCalls.callSites,
        )
        assertNotNull(
            edge(result.graph, "com.example.Sub", "com.example.Base", EdgeKind.FUNCTION_CALL),
            "super.m() resolves to the parent method",
        )
        assertEquals(0, result.stats.resolution.unresolved)
    }

    @Test
    fun `java var instanceof-pattern wildcard and try-with-resources`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Util.java" to """
                    package com.example;
                    import java.io.Closeable;
                    public class Util implements Closeable {
                        public void go() {}
                        public void close() {}
                    }
                """,
                    "Helper.java" to "package com.example; public class Helper {}",
                    "Main.java" to """
                    package com.example;
                    import java.util.List;
                    public class Main {
                        void run(List<? extends Helper> hs, Object o) throws Exception {
                            var u = new Util();
                            u.go();
                            if (o instanceof Helper h) { Helper copy = h; }
                            try (Util res = new Util()) { res.go(); }
                        }
                    }
                """,
                ),
            )
        assertNotNull(edge(result.graph, "com.example.Main", "com.example.Util", EdgeKind.CONSTRUCTOR_CALL))
        assertNotNull(edge(result.graph, "com.example.Main", "com.example.Util", EdgeKind.FUNCTION_CALL))
        assertNotNull(edge(result.graph, "com.example.Main", "com.example.Helper", EdgeKind.TYPE_REFERENCE))
        assertEquals(0, result.stats.resolution.unresolved)
    }

    @Test
    fun `unqualified static-imported java call resolves to the owner`() {
        val result =
            analyze(
                module(":lib:main", "U.java" to "package lib; public class U { public static void work() {} }"),
                module(
                    ":app:main",
                    "A.java" to """
                    package app;
                    import static lib.U.work;
                    public class A { void m() { work(); } }
                """,
                    dependencies = listOf(":lib:main"),
                ),
            )
        assertNotNull(edge(result.graph, "app.A", "lib.U", EdgeKind.FUNCTION_CALL))
        assertEquals(0, result.stats.resolution.unresolved)
    }
}
