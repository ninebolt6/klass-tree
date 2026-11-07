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
        val result =
            analyze(
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
            result.graph.nodes
                .filter { it.kind != NodeKind.FILE_SCOPE }
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
                    it.to ==
                    result.graph.nodes
                        .single { node -> node.fqName == "com.example.User" }
                        .id
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
        val result =
            analyze(
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
        val result =
            analyze(
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
        val callEdges =
            result.graph.edges.filter {
                it.from == b.id && it.to == a.id && it.kind == EdgeKind.FUNCTION_CALL
            }

        assertEquals(1, callEdges.size)
        assertEquals(1, callEdges.single().callSites.size)
        assertTrue(
            callEdges
                .single()
                .callSites
                .single()
                .file
                .endsWith("Calls.kt"),
        )
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
        val result = analyzeNodeRulesFixture()

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

    private fun analyzeNodeRulesFixture(): AnalysisResult =
        analyze(
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

    @Test
    fun `desugared calls and template references produce edges`() {
        val result =
            analyze(
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
        val result =
            analyze(
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

            fun moduleSpec(
                id: String,
                vararg roots: Path,
            ) = ModuleSpec(
                id = id,
                projectPath = id.substringBeforeLast(":"),
                sourceSetName = "main",
                moduleName = id.removePrefix(":").replace(":", "_"),
                sourceRoots = roots.map { it.toString() },
                classpath = listOf(stdlib()),
                dependencies = emptyList(),
            )
            val result =
                Analyzer().analyze(
                    AnalysisSpec(
                        projectRoot = root.toString(),
                        modules =
                            listOf(
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
                result.graph.edges
                    .filter { it.to == sharedClass.id }
                    .all { byId.getValue(it.from).module == "a" },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test sources see internal members of their own main like Kotlin friend paths`() {
        val result =
            analyze(
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

        val mainScope =
            result.graph.nodes.single {
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
        val result =
            analyze(
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
        val fileScope =
            result.graph.nodes.singleOrNull {
                it.kind == NodeKind.FILE_SCOPE && it.sourceFile == "src/app/main/Uses.kt"
            }
        assertTrue(result.graph.edges.none { it.from == uses.id || it.from == fileScope?.id })
    }

    @Test
    fun `compiler-generated members do not produce call edges`() {
        val result =
            analyze(
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
                it.from == uses.id &&
                    it.to == point.id &&
                    it.kind != EdgeKind.TYPE_REFERENCE
            },
        )
        assertTrue(
            result.graph.edges.any {
                it.from == uses.id && it.to == point.id && it.kind == EdgeKind.TYPE_REFERENCE
            },
        )
    }

    @Test
    fun `kotlin scripts are not analyzed`() {
        val result =
            analyze(
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
}
