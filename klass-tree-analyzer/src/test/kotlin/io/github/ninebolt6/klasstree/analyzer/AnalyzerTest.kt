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

class AnalyzerTest {
    @Test
    fun `builds source nodes and semantic edges without external nodes`() {
        val result = analyze(
            module(
                ":app:main",
                "Model.kt" to """
                package com.example

                interface Repository {
                    fun find(id: String): User?
                }

                class User(val id: String)

                class UserService(private val repository: Repository) {
                    fun find(id: String): User? = repository.find(id)
                }

                class UserIndex {
                    private val byName: Map<String, List<User>> = emptyMap()
                    fun usersNamed(name: String): List<User> = byName[name].orEmpty()
                }

                fun defaultUser(): User = User("default")
                """,
            ),
        )

        assertEquals(5, result.graph.nodes.size)
        assertEquals(
            setOf(
                "com.example.Repository" to NodeKind.INTERFACE,
                "com.example.User" to NodeKind.CLASS,
                "com.example.UserService" to NodeKind.CLASS,
                "com.example.UserIndex" to NodeKind.CLASS,
            ),
            result.graph.nodes.filter { it.kind != NodeKind.FILE_SCOPE }
                .map { it.fqName to it.kind }
                .toSet(),
        )
        assertNotNull(result.graph.nodes.singleOrNull { it.kind == NodeKind.FILE_SCOPE })
        assertTrue(result.graph.nodes.all { it.module == "app" && it.sourceSet == "main" })

        assertNotNull(edge(result.graph, "com.example.UserService", "com.example.Repository", EdgeKind.TYPE_REFERENCE))
        assertNotNull(edge(result.graph, "com.example.UserService", "com.example.Repository", EdgeKind.FUNCTION_CALL))
        assertNotNull(edge(result.graph, "com.example.UserService", "com.example.User", EdgeKind.TYPE_REFERENCE))
        assertNotNull(
            result.graph.edges.singleOrNull {
                it.kind == EdgeKind.CONSTRUCTOR_CALL &&
                    it.to == result.graph.nodes.single { node -> node.fqName == "com.example.User" }.id
            },
        )
        assertNotNull(edge(result.graph, "com.example.UserIndex", "com.example.User", EdgeKind.TYPE_REFERENCE))

        assertEquals(0, result.stats.resolution.analysisErrors.size)
        assertTrue(result.stats.resolution.resolvedExternal > 0)
        assertTrue(result.graph.edges.none { it.from == it.to })
    }

    @Test
    fun `resolves modules with differing classpaths`() {
        val junitJar = classpathEntries().first { it.contains("junit-jupiter-api") }
        val result = analyze(
            module(
                ":a:main",
                "a/A.kt" to """
                package a

                class A {
                    val values: List<String> = listOf("a")
                    fun joined(): String = values.joinToString()
                }
                """,
            ),
            module(
                ":b:main",
                "b/B.kt" to """
                package b

                import a.A

                class B(private val a: A) {
                    fun values(): List<String> = listOf(a.joined())
                }
                """,
                extraClasspath = listOf(junitJar),
                dependencies = listOf(":a:main"),
            ),
        )
        val moduleA = result.graph.nodes.single { it.fqName == "a.A" }
        val moduleB = result.graph.nodes.single { it.fqName == "b.B" }

        assertEquals(emptyList(), result.stats.resolution.analysisErrors)
        assertEquals(0, result.stats.resolution.unresolved)
        assertTrue(result.stats.resolution.resolvedExternal > 0)
        assertTrue(result.graph.edges.any { it.from == moduleB.id && it.to == moduleA.id })
    }

    @Test
    fun `qualified calls produce a single function call edge`() {
        val result = analyze(
            module(
                ":app:main",
                "Calls.kt" to """
                package com.example

                class A { fun g() = 1 }

                class B(val a: A) { fun h() = a.g() }
                """,
            ),
        )
        val a = result.graph.nodes.single { it.fqName == "com.example.A" }
        val b = result.graph.nodes.single { it.fqName == "com.example.B" }
        val callEdges = result.graph.edges.filter {
            it.from == b.id && it.to == a.id && it.kind == EdgeKind.FUNCTION_CALL
        }

        assertEquals(1, callEdges.size)
        assertEquals(1, callEdges.single().callSites.size)
        assertTrue(callEdges.single().callSites.single().file.endsWith("Calls.kt"))
        assertEquals(emptyList(), result.stats.resolution.analysisErrors)
    }

    @Test
    fun `produces every supported edge kind`() {
        val source = """
            package com.example

            annotation class Marker

            interface Shape

            open class Base

            class Circle : Base(), Shape

            @Marker
            class Annotated

            class User(val name: String)

            object Registry {
                val count = 0
            }

            data class Vec(val x: Int) {
                operator fun plus(other: Vec) = Vec(x + other.x)
                operator fun get(index: Int) = x
            }

            typealias Names = List<String>

            class Uses {
                fun a(u: User) = u.name
                fun b() = Registry.count
                fun c() = User::name
                fun d(v: Vec) = v + v
                fun e(v: Vec) = v[0]
                fun f(): Names = emptyList()
            }
        """
        val result = analyze(module(":app:main", "Model.kt" to source))

        assertNotNull(edge(result.graph, "com.example.Circle", "com.example.Base", EdgeKind.INHERITANCE))
        val toShape = edge(result.graph, "com.example.Circle", "com.example.Shape", EdgeKind.INHERITANCE)
        assertNotNull(toShape)
        assertNotNull(edge(result.graph, "com.example.Annotated", "com.example.Marker", EdgeKind.ANNOTATION))
        assertNotNull(edge(result.graph, "com.example.Uses", "com.example.User", EdgeKind.PROPERTY_ACCESS))
        assertNotNull(edge(result.graph, "com.example.Uses", "com.example.Registry", EdgeKind.PROPERTY_ACCESS))
        assertNotNull(edge(result.graph, "com.example.Uses", "com.example.User", EdgeKind.CALLABLE_REFERENCE))
        val operator = edge(result.graph, "com.example.Uses", "com.example.Vec", EdgeKind.OPERATOR_OR_SYNTHETIC_CALL)
        assertNotNull(operator)
        assertEquals(2, operator.callSites.size)
        assertNotNull(edge(result.graph, "com.example.Uses", "com.example.Names", EdgeKind.TYPE_REFERENCE))

        val lines = source.trimIndent().lines()
        val lineIndex = lines.indexOfFirst { it.contains("class Circle") }
        val column = lines[lineIndex].indexOf("Shape") + 1
        val site = toShape.callSites.single()
        assertEquals(lineIndex + 1, site.line)
        assertEquals(column, site.column)
    }

    @Test
    fun `applies the node rules`() {
        val result = analyze(
            module(
                ":app:main",
                "Model.kt" to """
                package com.example

                annotation class Marker

                interface Shape

                open class Base

                class Circle : Base(), Shape

                @Marker
                class Annotated

                class User(val name: String)

                object Registry {
                    val count = 0
                }

                enum class Color { RED }

                typealias Names = List<String>

                class Holder {
                    class Nested

                    fun make() {
                        val instance = object {
                            fun create() = User("anon")
                        }
                        instance.create()
                    }
                }

                class Uses {
                    fun local() {
                        class Local
                        Local()
                    }

                    fun a(u: User) = u.name
                }
                """,
                "TopLevel.kt" to """
                package com.example

                fun topLevel() = 1
                """,
            ),
        )

        val kinds = result.graph.nodes.associate { it.fqName to it.kind }
        assertEquals(NodeKind.ANNOTATION, kinds["com.example.Marker"])
        assertEquals(NodeKind.INTERFACE, kinds["com.example.Shape"])
        assertEquals(NodeKind.CLASS, kinds["com.example.Base"])
        assertEquals(NodeKind.CLASS, kinds["com.example.Circle"])
        assertEquals(NodeKind.OBJECT, kinds["com.example.Registry"])
        assertEquals(NodeKind.ENUM, kinds["com.example.Color"])
        assertEquals(NodeKind.TYPE_ALIAS, kinds["com.example.Names"])
        assertEquals(NodeKind.CLASS, kinds["com.example.Holder.Nested"])

        assertTrue(result.graph.nodes.none { it.name == "Local" })
        assertTrue(result.graph.nodes.none { it.name == "<anonymous>" })
        assertTrue(result.graph.nodes.none { it.name == "RED" })

        assertNotNull(edge(result.graph, "com.example.Holder", "com.example.User", EdgeKind.CONSTRUCTOR_CALL))

        val fileScopeNodes = result.graph.nodes.filter { it.kind == NodeKind.FILE_SCOPE }
        assertEquals(1, fileScopeNodes.size)
        assertTrue(fileScopeNodes.single().sourceFile?.endsWith("TopLevel.kt") == true)
    }

    @Test
    fun `desugared calls and template references produce edges`() {
        val result = analyze(
            module(
                ":app:main",
                "Model.kt" to """
                package com.example

                import com.example.Registry.count

                object Registry {
                    val count = 0
                }

                class Delegate {
                    operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = 0
                }

                class Items : Iterable<String> {
                    override fun iterator() = emptyList<String>().iterator()
                }

                class Parts {
                    operator fun component1() = "x"
                    operator fun component2() = 1
                }

                class Uses {
                    val delegated by Delegate()
                    fun total(): Int {
                        var t = 0
                        for (x in Items()) t++
                        return t
                    }
                    fun split(): Int {
                        val (name, num) = Parts()
                        return num
                    }
                    fun label() = "count: ${'$'}count"
                }
                """,
            ),
        )

        assertNotNull(
            edge(result.graph, "com.example.Uses", "com.example.Delegate", EdgeKind.OPERATOR_OR_SYNTHETIC_CALL),
        )
        assertNotNull(
            edge(result.graph, "com.example.Uses", "com.example.Items", EdgeKind.OPERATOR_OR_SYNTHETIC_CALL),
        )
        assertNotNull(
            edge(result.graph, "com.example.Uses", "com.example.Parts", EdgeKind.OPERATOR_OR_SYNTHETIC_CALL),
        )
        assertNotNull(
            edge(result.graph, "com.example.Uses", "com.example.Registry", EdgeKind.PROPERTY_ACCESS),
        )
        assertEquals(emptyList(), result.stats.resolution.analysisErrors)
    }

    @Test
    fun `collects supertype constructor arguments and ignores non-callable operators`() {
        val result = analyze(
            module(
                ":app:main",
                "Model.kt" to """
                package com.example

                class Helper {
                    fun h() = 1
                }

                open class Base(val n: Int)

                class Mid : Base(Helper().h())

                class Uses(val m: Mid) {
                    fun a() = m is Mid
                    fun b(x: Mid?) = x ?: m
                    fun c(x: Mid?) = x as Mid
                    fun d(a: Boolean, b: Boolean) = a && b
                    fun e(x: Mid?) = x!!.toString()
                }
                """,
            ),
        )

        assertNotNull(edge(result.graph, "com.example.Mid", "com.example.Helper", EdgeKind.CONSTRUCTOR_CALL))
        assertNotNull(edge(result.graph, "com.example.Mid", "com.example.Helper", EdgeKind.FUNCTION_CALL))
        assertNotNull(edge(result.graph, "com.example.Mid", "com.example.Base", EdgeKind.INHERITANCE))
        assertEquals(0, result.stats.resolution.unresolved)
        assertEquals(emptyList(), result.stats.resolution.analysisErrors)
    }

    @Test
    fun `a source root shared by several modules is claimed once`() {
        val root = Files.createTempDirectory("klass-tree-analyzer-test")
        try {
            val shared = root.resolve("shared").also { Files.createDirectories(it) }
            shared.resolve("Shared.kt").toFile().writeText(
                "package com.example\nclass Shared\nfun sharedTop() = Shared()\n",
            )
            val aSrc = root.resolve("a").also { Files.createDirectories(it) }
            aSrc.resolve("A.kt").toFile().writeText(
                "package com.example\nclass A { val s = Shared(); fun a() = sharedTop() }\n",
            )
            val bSrc = root.resolve("b").also { Files.createDirectories(it) }
            bSrc.resolve("B.kt").toFile().writeText(
                "package com.example\nclass B { val s = Shared(); fun b() = sharedTop() }\n",
            )
            fun moduleSpec(id: String, vararg roots: Path) = ModuleSpec(
                id = id,
                projectPath = id.substringBeforeLast(":"),
                sourceSetName = "main",
                moduleName = id.removePrefix(":").replace(":", "_"),
                sourceRoots = roots.map { it.toString() },
                classpath = listOf(stdlib()),
                dependencies = emptyList(),
            )
            val result = Analyzer().analyze(
                AnalysisSpec(
                    projectRoot = root.toString(),
                    modules = listOf(
                        moduleSpec(":a:main", aSrc, shared),
                        moduleSpec(":b:main", bSrc, shared),
                    ),
                ),
            )

            val sharedNodes = result.graph.nodes.filter { it.sourceFile == "shared/Shared.kt" }
            assertTrue(sharedNodes.isNotEmpty())
            assertTrue(sharedNodes.all { it.module == "a" })
            assertEquals(emptyList(), result.stats.resolution.analysisErrors)

            val byId = result.graph.nodes.associateBy { it.id }
            val sharedClass = result.graph.nodes.single { it.fqName == "com.example.Shared" }
            assertTrue(
                result.graph.edges.filter { it.to == sharedClass.id }
                    .all { byId.getValue(it.from).module == "a" },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test sources see internal members of their own main like Kotlin friend paths`() {
        val result = analyze(
            module(
                ":app:main",
                "Main.kt" to """
                package com.example

                internal fun internalHelper() = 1
                class Pub { fun p() = internalHelper() }
                """,
            ),
            module(
                ":app:test",
                "Test.kt" to """
                package com.example

                class AppTest {
                    fun t() = internalHelper()
                }
                """,
                dependencies = listOf(":app:main"),
            ),
        )

        val mainScope = result.graph.nodes.single {
            it.kind == NodeKind.FILE_SCOPE && it.sourceSet == "main"
        }
        val test = result.graph.nodes.single { it.fqName == "com.example.AppTest" }
        assertNotNull(
            result.graph.edges.singleOrNull {
                it.from == test.id && it.to == mainScope.id && it.kind == EdgeKind.FUNCTION_CALL
            },
        )
        assertEquals(0, result.stats.resolution.unresolved)
    }

    @Test
    fun `an import alone does not create a dependency edge`() {
        val result = analyze(
            module(
                ":app:main",
                "Dep.kt" to """
                package com.example

                class Unused
                """,
                "Uses.kt" to """
                package com.example

                import com.example.Unused

                class Uses
                """,
            ),
        )

        val uses = result.graph.nodes.single { it.fqName == "com.example.Uses" }
        val fileScope = result.graph.nodes.singleOrNull {
            it.kind == NodeKind.FILE_SCOPE && it.sourceFile == "src/app/main/Uses.kt"
        }
        assertTrue(result.graph.edges.none { it.from == uses.id || it.from == fileScope?.id })
    }

    @Test
    fun `compiler-generated members do not produce call edges`() {
        val result = analyze(
            module(
                ":app:main",
                "Model.kt" to """
                package com.example

                data class Point(val x: Int, val y: Int)

                class Uses {
                    fun a(p: Point) = p.copy(x = 1)
                    fun b(p: Point, q: Point) = p == q
                    fun c(p: Point) { val (x, y) = p; x + y }
                }
                """,
            ),
        )

        val point = result.graph.nodes.single { it.fqName == "com.example.Point" }
        val uses = result.graph.nodes.single { it.fqName == "com.example.Uses" }
        assertTrue(
            result.graph.edges.none {
                it.from == uses.id && it.to == point.id &&
                    it.kind != EdgeKind.TYPE_REFERENCE
            },
        )
        assertTrue(result.graph.edges.any { it.from == uses.id && it.to == point.id && it.kind == EdgeKind.TYPE_REFERENCE })
    }

    @Test
    fun `kotlin scripts are not analyzed`() {
        val result = analyze(
            module(
                ":app:main",
                "script.kts" to """
                val x = 1
                """,
                "Real.kt" to """
                package com.example

                class Real
                """,
            ),
        )

        assertEquals(1, result.stats.sourceFileCount)
        assertTrue(result.graph.nodes.all { it.sourceFile == "src/app/main/Real.kt" })
    }

    @Test
    fun `java sources produce nodes and edges in both directions`() {
        val result = analyze(
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

        val names = result.graph.nodes.filter { it.kind != NodeKind.FILE_SCOPE }.map { it.fqName to it.kind }.toSet()
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
        val result = analyze(
            module(":app:main", "J.java" to "package com.example; class J {}"),
            includeJavaSources = false,
        )
        assertTrue(result.graph.nodes.none { it.fqName == "com.example.J" })
    }

    @Test
    fun `java anonymous class construction still reaches the named supertype`() {
        val result = analyze(
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
    fun `java call to a JvmName facade reaches the file scope node`() {
        val result = analyze(
            module(
                ":app:main",
                "K.kt" to """
                    @file:JvmName("Helpers")
                    package com.example

                    fun topLevel() {}
                """,
                "Caller.java" to """
                    package com.example;
                    public class Caller {
                        void call() { Helpers.topLevel(); }
                    }
                """,
            ),
        )
        val fileScope = result.graph.nodes.single { it.kind == NodeKind.FILE_SCOPE }
        val caller = result.graph.nodes.single { it.fqName == "com.example.Caller" }
        assertTrue(
            result.graph.edges.any { it.from == caller.id && it.to == fileScope.id && it.kind == EdgeKind.FUNCTION_CALL },
            "@JvmName facade calls must resolve to the FILE_SCOPE node, got edges " + result.graph.edges,
        )
    }

    @Test
    fun `java throws static-import field and enum edges`() {
        val result = analyze(
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
        assertEquals(NodeKind.ENUM, result.graph.nodes.single { it.fqName == "lib.E" }.kind)
        assertEquals(NodeKind.CLASS, result.graph.nodes.single { it.fqName == "lib.Boom" }.kind)
    }

    @Test
    fun `java nested classes and constructor chaining`() {
        val result = analyze(
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
        val result = analyze(
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
        val result = analyze(
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
        val result = analyze(
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
        val result = analyze(
            module(
                ":app:main",
                "Ok.java" to "package com.example; public class Ok {}",
                "Broken.java" to "package com.example; public class {{{ not java at all",
            ),
        )
        assertTrue(result.stats.resolution.analysisErrors.isNotEmpty())
        assertNotNull(result.graph.nodes.singleOrNull { it.fqName == "com.example.Ok" })
    }

    @Test
    fun `package-info java and empty java file add no nodes`() {
        val result = analyze(
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
        val result = analyze(
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
    fun `kotlin sees java members as calls and property accesses`() {
        val result = analyze(
            module(
                ":app:main",
                "Bean.java" to """
                    package com.example;
                    public class Bean {
                        public static int total = 0;
                        public String name = "";
                        public static Bean create() { return new Bean(); }
                    }
                """,
                "Use.kt" to """
                    package com.example

                    fun use(b: Bean): String {
                        Bean.total = Bean().name.length
                        Bean.create()
                        return b.name
                    }
                """,
            ),
        )
        assertNotNull(
            edge(result.graph, "com.example", "com.example.Bean", EdgeKind.CONSTRUCTOR_CALL),
            "Kotlin sees the Java constructor",
        )
        assertNotNull(
            edge(result.graph, "com.example", "com.example.Bean", EdgeKind.PROPERTY_ACCESS),
            "Kotlin sees Java public fields as property accesses",
        )
    }

    @Test
    fun `java generics varargs chained and enum-switch calls`() {
        val result = analyze(
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
        val calls = result.graph.edges.filter {
            it.from == result.graph.nodes.single { n -> n.fqName == "com.example.Main" }.id &&
                it.to == result.graph.nodes.single { n -> n.fqName == "com.example.Util" }.id &&
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
        val result = analyze(
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
        assertEquals(NodeKind.ANNOTATION, result.graph.nodes.single { it.fqName == "com.example.Ann" }.kind)
        assertNotNull(
            edge(result.graph, "com.example.Use", "com.example.Target", EdgeKind.TYPE_REFERENCE),
            "annotation argument type",
        )
    }

    @Test
    fun `java classes with equal simple names in different packages stay distinct`() {
        val result = analyze(
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
        val result = analyze(
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
    fun `kotlin inherits java interface and java reaches kotlin object`() {
        val result = analyze(
            module(
                ":app:main",
                "Spec.java" to "package com.example; public interface Spec { boolean ok(); }",
                "Impl.kt" to """
                    package com.example

                    class Impl : Spec {
                        override fun ok() = true
                    }
                """,
                "Ids.kt" to """
                    package com.example

                    object Ids { const val prefix = "x" }
                """,
                "UseIds.java" to """
                    package com.example;
                    public class UseIds { String p = Ids.prefix; }
                """,
            ),
        )
        assertNotNull(
            edge(result.graph, "com.example.Impl", "com.example.Spec", EdgeKind.INHERITANCE),
            "Kotlin class implementing a Java interface",
        )
        assertNotNull(
            edge(result.graph, "com.example.UseIds", "com.example.Ids", EdgeKind.PROPERTY_ACCESS),
            "Java reading a Kotlin object const",
        )
        assertEquals(0, result.stats.resolution.unresolved)
    }

    @Test
    fun `java static initializer super-method and anonymous-body calls`() {
        val result = analyze(
            module(
                ":app:main",
                "Util.java" to "package com.example; public class Util { public static void boot() {} public void m() {} }",
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
        val bootCalls = result.graph.edges.single {
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
    fun `java record and kotlin companion access`() {
        val result = analyze(
            module(
                ":app:main",
                "Pair.java" to "package com.example; public record Pair(int a, int b) {}",
                "K.kt" to """
                    package com.example

                    class K {
                        companion object {
                            @JvmStatic
                            fun make() = K()
                        }
                    }
                """,
                "Use.java" to """
                    package com.example;
                    public class Use {
                        Pair p = new Pair(1, 2);
                        int total = p.a();
                        K k = K.make();
                    }
                """,
            ),
        )
        assertNotNull(edge(result.graph, "com.example.Use", "com.example.Pair", EdgeKind.CONSTRUCTOR_CALL))
        assertNotNull(
            edge(result.graph, "com.example.Use", "com.example.Pair", EdgeKind.FUNCTION_CALL),
            "record accessor",
        )
        assertNotNull(
            edge(result.graph, "com.example.Use", "com.example.K", EdgeKind.FUNCTION_CALL),
            "@JvmStatic companion call",
        )
        assertEquals(0, result.stats.resolution.unresolved)
    }

    @Test
    fun `java var instanceof-pattern wildcard and try-with-resources`() {
        val result = analyze(
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
        val result = analyze(
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

    @Test
    fun `java reaches kotlin companion through Companion and annotations cross both ways`() {
        val result = analyze(
            module(
                ":app:main",
                "K.kt" to """
                    package com.example

                    annotation class KAnn

                    class K {
                        companion object { fun make() = K() }
                    }
                """,
                "JAnn.java" to "package com.example; public @interface JAnn {}",
                "Use.java" to """
                    package com.example;
                    @KAnn
                    public class Use { K k = K.Companion.make(); }
                """,
                "Use2.kt" to """
                    package com.example

                    @JAnn
                    class Use2
                """,
            ),
        )
        assertNotNull(
            edge(result.graph, "com.example.Use", "com.example.K.Companion", EdgeKind.FUNCTION_CALL),
            "companion call lands on the companion node, as in Kotlin",
        )
        assertNotNull(
            edge(result.graph, "com.example.Use", "com.example.KAnn", EdgeKind.ANNOTATION),
            "Java class annotated with a Kotlin annotation",
        )
        assertNotNull(
            edge(result.graph, "com.example.Use2", "com.example.JAnn", EdgeKind.ANNOTATION),
            "Kotlin class annotated with a Java annotation",
        )
        assertEquals(0, result.stats.resolution.unresolved)
    }

    @Test
    fun `includeJavaSources false still analyzes kotlin fully`() {
        val result = analyze(
            module(
                ":app:main",
                "A.java" to "package com.example; public class A {}",
                "K.kt" to "package com.example\nclass K(val s: String)",
            ),
            includeJavaSources = false,
        )
        assertNotNull(result.graph.nodes.singleOrNull { it.fqName == "com.example.K" })
        assertTrue(result.graph.nodes.none { it.fqName == "com.example.A" })
    }

    private fun module(
        id: String,
        vararg sources: Pair<String, String>,
        extraClasspath: List<String> = emptyList(),
        dependencies: List<String> = emptyList(),
    ) = TestModule(id, sources.toList(), extraClasspath, dependencies)

    private data class TestModule(
        val id: String,
        val sources: List<Pair<String, String>>,
        val extraClasspath: List<String>,
        val dependencies: List<String>,
    )

    private fun analyze(
        vararg modules: TestModule,
        includeJavaSources: Boolean = true,
    ): AnalysisResult {
        val root = Files.createTempDirectory("klass-tree-analyzer-test")
        try {
            val spec = AnalysisSpec(
                projectRoot = root.toString(),
                includeJavaSources = includeJavaSources,
                modules = modules.map { module ->
                    val sourceSetName = module.id.substringAfterLast(":")
                    val projectPath = module.id.substringBeforeLast(":").ifEmpty { ":" }
                    val label = projectPath.removePrefix(":").ifEmpty { "root" }
                    val sourceRoot = root.resolve("src/$label/$sourceSetName")
                    module.sources.forEach { (path, content) ->
                        val file = sourceRoot.resolve(path)
                        Files.createDirectories(file.parent)
                        file.toFile().writeText(content.trimIndent())
                    }
                    ModuleSpec(
                        id = module.id,
                        projectPath = projectPath,
                        sourceSetName = sourceSetName,
                        moduleName = "${label}_${sourceSetName}".replace(Regex("[^A-Za-z0-9_]"), "_"),
                        sourceRoots = listOf(sourceRoot.toString()),
                        classpath = listOf(stdlib()) + module.extraClasspath,
                        dependencies = module.dependencies,
                    )
                },
            )
            return Analyzer().analyze(spec)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun edge(
        graph: DependencyGraph,
        fromFqName: String,
        toFqName: String,
        kind: EdgeKind,
    ): GraphEdge? {
        val from = graph.nodes.single { it.fqName == fromFqName }
        val to = graph.nodes.single { it.fqName == toFqName }
        return graph.edges.singleOrNull {
            it.from == from.id && it.to == to.id && it.kind == kind
        }
    }

    private fun classpathEntries(): List<String> =
        System.getProperty("java.class.path").split(File.pathSeparator)

    private fun stdlib(): String = classpathEntries().first { it.contains("kotlin-stdlib") }
}
