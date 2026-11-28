# klass-tree 機能実装戦略: 市場ギャップと検証すべき前提

作成日: 2026-11-28  
対象: Kotlin/JVM プロジェクト向けソースレベル依存グラフ

## 結論

klass-tree が狙うべき市場は、汎用のモジュールグラフでも、lintでも、CodeQLのような汎用クエリ基盤でもない。残っている隙間は、**Gradle内で完結し、Kotlinソースの意味解決に基づく依存関係を、段階的なアーキテクチャ改善ワークフローへ直接つなげる軽量な仕組み**である。

ただし、この差別化は現時点では仮説である。現在の実装には、精度未検証、compiler plugin/language settings未反映、node IDのリファクタリング耐性不足、member粒度不足、build cache非対応、Gradle Isolated Projectsと相性が悪いプラグイン構造という前提リスクがある。したがって、HTMLレポートやルール強化より先に、**精度corpus、schema identity/粒度、互換性matrix、Gradle実行モデル**を検証する。

初期ターゲットは **Kotlin server-side / JVM backend** に絞る。Android/KMPは市場として大きいが、Composeなどのcompiler plugin、KSP/kapt生成コード、variant、expect/actualを扱う必要があり、現行の解析モデルのまま拡大しない。

## 現行実装が置かれている前提

戦略は現状の強みだけでなく、次の制約を前提に立てる。

### 精度はまだ証明されていない

直近15コミットの多くは、delegation、SAM、typealias constructor、enum生成メンバ、Java getter、generic継承など解析漏れの修正である。これはKotlin semantic analysisの必要性を示す一方、「正確なグラフがある」という主張が実プロジェクトで検証済みであることは意味しない。

必要なのは、自前fixtureだけでなく実OSSを対象にした精度corpusである。少なくとも次を測る。

- unresolved reference率とanalysis error率
- コンパイル済みbytecodeが持つ参照との差分
- Kotlin↔Java相互参照のrecall
- typealias、delegation、generated source、annotation use-site、operator/synthetic callの回帰
- kotlinx.serialization、all-open/no-argなどcompiler pluginを使うfixtureでの挙動

### source semanticとbytecodeは上下ではなくトレードオフ

bytecode解析はKotlin固有構文を失いやすい一方、compilerが実際に生成したものを見る。source semantic解析は、元のKotlin構造と正確なソース位置を保持しやすい一方、compiler pluginが生成/変換する宣言をそのままでは見えない可能性がある。

現行の`AnalysisSpec`はsource roots、classpath、module dependenciesだけを持ち、`AnalysisSession.kt`は`JvmPlatforms.defaultJvmPlatform`でmoduleを作る。language version、`-X`系フラグ、opt-in、compiler plugin設定は渡していない。Analysis API Standaloneのcompiler plugin対応自体も限られているため、これは実装漏れというだけでなく製品戦略上の中核リスクである。

### baselineは「行移動」には強いが「移動/改名」には弱い

baselineは`(from, to, kind)`をキーにcall-site countを比較する。現行node IDは`<module>:<relative-source-path>#<KIND>:<fq-name>`なので、ファイル移動、クラス改名、モジュール移動、source set変更で同一宣言でもIDが変わる。旧edgeはprogress、新edgeは`0 -> N`の増加になり、deny ruleに該当すれば純粋なリファクタリングで`klassTreeCheck`が失敗し得る。

したがって現行の性質は「無関係な行移動では壊れにくい」までで、「リファクタリング耐性がある」ではない。schemaを公開固定する前に、logical identityとphysical locationを分ける必要がある。

なお、単純なfqNameベースIDだけでも解決しない。`FILE_SCOPE`は本質的にファイルへ結び付き、同一fqNameが複数moduleに存在し得る。member-level identityを導入するか、file moveを表すalias/matchingを持つかを設計する必要がある。

### 現在の「宣言レベル」は実質class/file-scopeレベル

現行モデルではmemberはownerのclass/object/fileへ集約される。`CallSite`は`file/line/column`だけで、参照先のmember signatureを持たない。この粒度でもarchitecture driftの一部は見えるが、「どのmethodに依存しているか」「member単位のimpact」「最小カット」には不足する。

schemaを固定する前に、少なくとも次を決める。

- edgeまたはcallSiteにtarget member signatureを持たせるか
- memberをnodeにするか、edge属性に留めるか
- owner側のmemberも記録するか
- overload、extension、constructor、typealias展開後の表現

### Gradle実行モデルが公開後の bottleneck になる

`KlassTreeTask`とmodule spec taskには`@CacheableTask`がない。up-to-date判定用のinput/outputはあるが、CIのclean checkoutやremote build cacheでは再利用できない。ただし、`projectRoot`のようなabsolute path inputや出力内のabsolute pathがあるため、annotationを付けるだけでキャッシュが効くとは限らない。relocatability auditとセットで扱う。

また、プラグインはrootで`allprojects`、`afterEvaluate`、`projectsEvaluated`を使い、他projectの状態を読む。Gradle Isolated Projectsのcross-project access制約と相性が悪い。公開すると大規模利用者ほどここに当たるため、各projectがspec artifactを出し、rootがdependency経由で集約する構造を検討する。

### composite buildは「ソース解析されない」が「classpathに乗らない」ではない

現行プラグインがscanするのはroot buildの`allprojects`なので、included build内のprojectはsource moduleとして解析されない。一方、classpath artifact viewは別buildの`ProjectComponentIdentifier`を除外しないため、included buildのartifactはbinary dependencyとして残る設計である。したがって課題は「参照解決が全部できない」ではなく、「included build内の宣言がsource-level nodeにならずexternal扱いになる」ことである。

## 市場観察: 差別化は狭いが存在する

| ツール/カテゴリ | 強み | klass-treeとの重なり | 残る差別化の示唆 |
| --- | --- | --- | --- |
| [CodeQL for Java/Kotlin](https://codeql.github.com/docs/codeql-language-guides/codeql-for-java/) | 意味解決済みcall graph、強力なquery言語、GitHub code scanning/SARIF導線。 | 「semantic + query + SARIF + CI」の大部分と重なる。 | klass-treeは汎用queryで勝たない。Gradle内完結、アーキテクチャratchet、軽量さ、修正ワークフローに絞る。 |
| [dependency-analysis-gradle-plugin](https://github.com/autonomousapps/dependency-analysis-gradle-plugin/wiki/Tasks) | Android/JVMで導入実績があり、`reason`は依存adviceの原因クラスやshortest pathを示す。 | 「モジュール依存の原因に降りる」価値の一部。 | ABI/dependency adviceではなく、ソース参照種別とarchitecture driftの継続監視に絞る。 |
| IntelliJ [Dependency Structure Matrix](https://www.jetbrains.com/help/idea/dsm-analysis.html) / Analyze Dependency | 多くの開発者が既に持ち、対話的な原因調査ができる。 | ローカルな依存調査と影響分析。 | klass-treeはCI、reviewable artifact、baseline、team rulesに価値を置く。IDE調査の代替にしない。 |
| [ArchUnit](https://www.archunit.org/userguide/html/000_Index.html) / [Konsist](https://docs.konsist.lemonappdev.com/writing-tests/architecture-assert) | 成熟したrule/test導線。KonsistはKotlinネイティブでKMPも扱う。 | layer/rule enforcement。 | rule DSLを広く作り込まない。klass-treeはgraphとratchetを中核にし、最小限のruleだけを載せる。 |
| Gradle module graph系 / [Aalekh](https://github.com/shivathapaa/Aalekh) / module-graph-assert | module構造のreport、rules、metrics、CI出力が手軽。AalekhはbaselineやSARIFも持つ。 | module architecture governance。 | module-onlyに深入りしない。klass-treeはmodule違反の原因となるsource referenceへ降りることを目指す。 |
| [Sonargraph](https://www.hello2morrow.com/products/sonargraph/architect) / SonarQube architecture | 商用の成熟した可視化、rules、metrics、baseline。SonarはStructure101取得後、architecture機能を統合している。 | architecture governance全般。 | オープン、ローカル完結、Kotlin-first、Gradle-nativeであることを明確にする。総合 commercial suiteにはならない。 |
| SCIP/semanticdb-kotlinc / [Kotlin LSP](https://kotlinlang.org/docs/kotlin-lsp.html) | 定義・参照・位置を機械可読に扱う標準/基盤。Kotlin LSPはcall hierarchyも持つ。 | machine-readable reference dataとagent導線。 | klass-treeの価値は生の参照データそのものではなく、architecture用に正規化したedge、baseline、CI運用。exportは独自DSLより標準形式を優先する。 |
| 新興semantic graph系（[kartograph](https://github.com/ictechgy/kartograph), [ktrics](https://github.com/koji-1009/ktrics)） | 到達性、dead code、metrics、agent向けqueryなどへの志向が強い。 | semantic dependency intelligence全般。 | 市場が動いているため、単なるJSON出力は差別化にならない。workflowと精度を検証する必要がある。 |

## 市場に残っているギャップ

上の競合を踏まえると、ギャップは最初に想定したより狭い。

1. **Gradle-nativeなprogressive modernization**: 新規悪化だけを止め、改善を可視化しながらレガシー依存を減らす運用。
2. **architecture向けに正規化されたソース参照**: 生のfind referencesではなく、owner class/file、edge kind、call-site evidence、module/package pathへ整形済みのデータ。
3. **Kotlinソース位置と構造の保持**: top-level function、typealias、operator/synthetic call、Kotlin↔Java参照を、修正可能なソース位置へ結び付ける。
4. **ローカル/CI完結のオープンなworkflow**: commercial platform、外部index service、独自クエリ言語を必須にしない。

逆に、次はもはや単独では差別化にならない。

- machine-readable JSONを出すこと
- semantic analysisをすること
- SARIFを出すこと
- interactive graphを持つこと

## klass-treeの価値仮説

> klass-treeは、Kotlin server-side / JVM backendのmonorepoを対象に、Gradle内で依存グラフを生成し、正確なソース証拠付きでアーキテクチャの悪化を止め、段階的な改善を運用できるようにする。

これは現時点では価値仮説であり、精度corpusと需要検証で裏付ける。検証前に「compiler-grade」「declaration-level」「refactoring-safe」とは呼ばない。

明示的に狙わない領域:

- ktlint/detektのようなstyle/general lint
- CodeQLのような汎用query language
- Gradle module graphだけの可視化
- 全言語対応のcommercial architecture suite
- サーバー常駐型のcentral analysis platform

## 機能実装ロードマップ

### Phase 0: 前提検証と公開準備

| 優先度 | 機能/作業 | 目的 | 完了条件 |
| --- | --- | --- | --- |
| P0 | 実OSS precision corpus | 差別化の根拠となる精度を測る。 | 3〜5件のserver-side Kotlin OSSでunresolved率、bytecode参照との差分、failure modeを記録できる。 |
| P0 | compiler settings/plugin spike | 解析不能の最大リスクを切り分ける。 | languageVersion、opt-in、`-X` flag、serialization/all-open fixtureで何が解決できないか分かる。Analysis API側の制約なら代替案を決める。 |
| P0 | schema identity/粒度設計 | 後方互換性の悪いschemaを公開固定しない。 | logical node identity、FILE_SCOPEの扱い、target member粒度、module moveの扱いをADR化する。 |
| P0 | KGP/Analysis API compatibility matrix | 公開後のサポート負債を限定する。 | 対応Kotlin minor、Analysis API version、不一致時の明示的errorを決める。 |
| P0 | build cache/relocatability audit | CIコストを下げる安価な増分施策を先に取る。 | `@CacheableTask`の可否、absolute pathの排除、per-module output分割の設計がある。 |
| P0 | Isolated Projects/configuration cache検証 | 大規模Gradleユーザーで詰まる構造を把握する。 | 現行構造の違反点と、spec artifact集約方式の移行案がある。 |
| P0 | 需要検証 | ツール比較だけでPhase 1の順を決めない。 | 3チーム/3リポジトリにpreviewを試し、HTML、export、rules、queryのどれを実際に使ったかを記録する。 |

### Phase 1: データを consumable にする

| 優先度 | 機能 | 理由 | 実装メモ |
| --- | --- | --- | --- |
| P1 | Plugin Portal / Maven Central公開 | 検証と採用には配布が必要。ただしmatrixと既知の制約を先に公開する。 | snapshot/previewとして出し、schema stabilityと対応versionを明記する。 |
| P1 | 汎用export | 独自query DSLを作る前に、既存ツールで検証できるようにする。 | GraphML/CSVを最初の候補にし、SQLite exportをspikeする。JSONはcanonical artifactとして継続。 |
| P1 | オフラインHTMLレポート MVP | 探索価値は高いが、IDとmember粒度に依存する。 | schema設計後に作る。module/package/class階層、edge kind filter、検索、call-site panel。 |
| P2 | 内部query/index API | HTML、rules、exportで重複する走査を避ける。 | public DSLとしては売らず、neighbor/path/impact/SCCの内部reuseに留める。 |
| P2 | 公開query CLI/DSL | CodeQLや汎用graph toolsと競合しやすい。 | export利用者の具体的需要が出てから判断する。 |

### Phase 2: 最小限のガバナンス

| 優先度 | 機能 | 理由 | 実装メモ |
| --- | --- | --- | --- |
| P1 | move/identity-aware baseline | 現行baselineの主要な誤検出を止める。 | schema identity設計に従う。file/module移動時の互換移行も決める。 |
| P1 | rules v1（最小範囲） | deny prefixだけでは不足だが、広いrule DSLはscope creep。 | package/module/layer matcher、allowed dependency、edge kind条件、`because`メッセージに限定する。 |
| P1 | approved exceptions | baselineに例外の意図を混ぜない。 | owner、reason、expiresAt、対象rule/edge kindを持つ。 |
| P1 | SARIF/JUnit出力 | CIで actionable にする。 | violation edge単位のresultに現行graphのcallSitesをrelated locationsとして出し、「新規箇所だけ」と誤解されない表現にする。新規箇所の特定にはfull graph diffかbaseline schema拡張が必要。 |
| P2 | PR diff summary | 追加/削除依存、count変化、新規cycleをレビュー単位で説明する。 | machine-readable diffを先に安定させ、PR commentは薄いadapterにする。 |
| P2 | trend metrics | 改善の継続を可視化する。 | dependency count、cycle count、denied call-site countをJSON/CSVで出す。DBやSaaSにはしない。 |

### Phase 3: スケールと対象市場の拡大

| 優先度 | 機能 | 理由 | 実装メモ |
| --- | --- | --- | --- |
| P1-P2 | build cache + per-module output分割 | full analysisの常識的な削減。独自増分解析より先に行う。 | cache correctness、relocatability、module input fingerprintを先に整える。 |
| P2 | 増分解析 | 大規模monorepoでの採用条件。 | cross-module resolutionを壊さないかcorpusで検証してから実装する。 |
| P2 | included buildのsource-level analysis | composite buildの宣言がexternal扱いになる制約を減らす。 | build boundaryをまたぐmodule spec交換が必要。Isolated Projects設計と一緒に考える。 |
| P2-P3 | Android/AGP variant対応 | Kotlin最大市場だが、compiler pluginとgenerated sourceが前提。 | compiler plugin方針とserver-sideでの精度検証が済むまで着手しない。 |
| P3 | KMP source set対応 | 市場ギャップはあるが複雑度が最も高い。 | expect/actual、source set hierarchy、platform classpathの調査spikeから始める。 |
| P3 | IDE plugin | feedback loopは短いが保守コストが高い。 | IntelliJ既存機能と重なるため、CI artifactとの補完関係が証明されてから検討する。 |

## アーキテクチャ上の指針

1. **精度を最優先の製品要件にする。** semantic correctnessのregression corpusを、新機能より先に増やす。
2. **schemaは公開固定までexperimental扱いにする。** logical identity、member粒度、compiler設定の表現を決めるまで semantic versioning 上の安定契約にしない。
3. **canonical artifactはJSONのままにする。** ただし公開インターフェースとしてのquery DSLより、汎用exportを優先する。
4. **内部query/index層は作ってよいが、製品価値として売らない。** HTML、rules、exportの共通土台に留める。
5. **rule engineは最小限にする。** ArchUnit/Konsist/Sonarの厚い領域へ広がらず、ratchetとevidenceに強い部分だけを提供する。
6. **Gradleのmodern execution modelに合わせる。** configuration cache、build cache、Isolated Projectsを公開前の設計要件にする。
7. **source semanticとbytecodeの限界を両方説明する。** compiler plugin/generated codeを含む精度比較を行い、必要ならbytecode cross-checkを補助信号にする。

## 成功指標

- 対応matrix内のプロジェクトで、初回導入から`graph.json`生成まで10分以内。
- precision corpusでunresolved率、external/skip理由、bytecode参照差分を継続計測できる。
- compiler plugin fixtureで、解決できるものとAnalysis API制約で解決できないものが明確に分かれている。
- ファイル内の行移動では`klassTreeCheck`が失敗しない。さらにschema移行後は、class/file移動に対する失敗率をcorpusで測定できる。
- CI違反にrule reasonと少なくとも1つのcurrent call siteが表示される。
- 変更が1モジュールだけのCI runで、build cacheまたはper-module reuseによりfull analysisを避けられる。
- previewユーザーがHTML、export、rules、queryのどれを実際に使ったか分かる。

## 最初に切るべき実装チケット

1. **Accuracy corpus harness**: OSS fixture取得、unresolved率、bytecode参照差分、結果JSON。
2. **Compiler configuration spike**: languageVersion、opt-in、`-X`、compiler pluginの現状限界を再現するfixture。
3. **Graph identity and member-granularity ADR**: stable identity、`FILE_SCOPE`、target member、module moveを決める。
4. **Compatibility matrix and version validation**: KGP/Analysis APIの対応範囲、不一致時の明示的error。
5. **Build cache and relocatability audit**: `@CacheableTask`、absolute path、per-module output分割の設計。
6. **Isolated Projects design spike**: root横断scanをやめ、spec artifactを集約する方式のPoC。
7. **Preview validation script**: 3リポジトリへの導入手順、利用出力、アンケート/観察項目。
8. **Generic export MVP**: GraphMLまたはCSV。SQLiteは需要を見て追加。

## 主要リスクと対策

| リスク | 影響 | 対策 |
| --- | --- | --- |
| Analysis API Standaloneの安定性 | `StandaloneWorkaroundApi`、`MockProject`サービス差し替え、`ResolveScopeManager`依存がKotlin更新で壊れ得る。 | 対応versionを絞り、CI matrixを回す。内部API依存点を隔離し、upstream issueを追跡する。 |
| compiler plugin非対応 | serialization、Compose、all-open/no-argなどで生成memberが見えず、主張する精度を満たせない。 | Phase 0で限界を証明し、対応可能な設定伝播、 explicit limitation、bytecode cross-checkのどれで行くか決める。 |
| schemaを早く固定しすぎる | ID/粒度の誤りが公開後のbreaking changeになる。 | schemaはexperimental扱いにし、ADRとcorpusの後で固定する。 |
| CodeQL/IDE/商用ツールとの重複 | semantic/query/reportだけでは差別化できない。 | Gradle-native ratchet、ソース証拠、段階的modernizationにscopeを絞る。 |
| Gradle execution model | 大規模利用者ほどcache/IPの問題に当たる。 | 公開前にconfiguration cache/build cache/Isolated Projectsの互換性を検証する。 |
| 需要仮説が外れる | HTMLやrulesを作っても使われない。 | preview導入の観察結果でPhase 1の順序を変える。 |

## 参考

- [CodeQL for Java and Kotlin](https://codeql.github.com/docs/codeql-language-guides/codeql-for-java/)
- [dependency-analysis-gradle-plugin Tasks](https://github.com/autonomousapps/dependency-analysis-gradle-plugin/wiki/Tasks)
- [IntelliJ Dependency Structure Matrix](https://www.jetbrains.com/help/idea/dsm-analysis.html)
- [Kotlin Language Server](https://kotlinlang.org/docs/kotlin-lsp.html)
- [ArchUnit User Guide](https://www.archunit.org/userguide/html/000_Index.html)
- [ArchUnit issue: Support for Kotlin toplevel functions](https://github.com/TNG/ArchUnit/issues/528)
- [Konsist Architecture Assertion](https://docs.konsist.lemonappdev.com/writing-tests/architecture-assert)
- [Aalekh](https://github.com/shivathapaa/Aalekh)
- [Sonargraph Architect](https://www.hello2morrow.com/products/sonargraph/architect)
- [Sonargraph Kotlin specific issues](https://eclipse.hello2morrow.com/doc/standalone/content/ch04s03s02.html)
- [Sonar acquires Structure101](https://www.sonarsource.com/company/press-releases/sonar-acquires-structure101-to-strengthen-code-quality-offering/)
- [Gradle Isolated Projects](https://docs.gradle.org/current/userguide/isolated_projects.html)
- [Gradle best practices: avoid afterEvaluate](https://docs.gradle.org/current/userguide/best_practices_general.html)
- [Analysis API compiler plugin discussion in detekt](https://github.com/detekt/detekt/issues/7404)
- [kartograph](https://github.com/ictechgy/kartograph)
- [ktrics](https://github.com/koji-1009/ktrics)
