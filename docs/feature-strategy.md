# klass-tree 機能実装戦略: 市場ギャップからの逆算

作成日: 2026-11-28  
対象: Kotlin/JVM プロジェクト向けソースレベル依存グラフ

## 結論

klass-tree は「汎用のモジュール依存グラフ」や「Kotlin linter」として競合するべきではない。価値の中心は、**Kotlin Analysis API で意味解決した宣言レベルの依存関係を、正確なコールサイト証拠付きで再利用可能なデータにすること**にある。

市場には、モジュール単位の可視化、アーキテクチャルールのテスト、商用の総合アーキテクチャ管理はある。一方で、Kotlin固有の意味論を保ったまま、宣言・呼び出し・継承・アノテーションを横断し、CIで段階的に運用できるオープンな仕組みは少ない。klass-tree はこの隙間で「Kotlin semantic dependency intelligence」に特化すべきである。

実装優先順位は次の通り。

1. **配布と導入体験**: Gradle Plugin Portal / Maven Central への公開、バージョン付きリリース、最小セットアップ。
2. **クエリ基盤と安定スキーマ**: `graph.json` を製品の中心に据え、依存元/依存先、影響範囲、経路、循環を再利用可能なライブラリとして提供する。
3. **オフラインHTMLレポート**: 生JSONとDOTだけでは価値が届かないため、検索・フィルタ・ドリルダウン・コールサイト証拠を備える。
4. **アーキテクチャガバナンスv1**: レイヤー/パッケージルール、循環検出、edge kind条件、例外承認を既存baseline ratchetと統合する。
5. **CI連携**: SARIF/JUnit/XML差分レポートとPR annotationで、違反をレビュー可能な形にする。
6. **スケール**: モジュール単位の増分解析、コンテンツ fingerprint、並列化、ベンチマーク。
7. **Android/KMP**: JVMでの精度・性能・運用価値を証明した後に拡張する。KMPを早期の主力機能にしない。

## 市場観察

| ツール/カテゴリ | 強み | 残っているギャップ | klass-treeへの示唆 |
| --- | --- | --- | --- |
| [ArchUnit](https://www.archunit.org/userguide/html/000_Index.html) | Java bytecodeを対象にした成熟したアーキテクチャテスト。JUnit導線が強い。 | Java中心。Kotlin top-level functionは生成クラス経由になり扱いにくいことが[公開issue](https://github.com/TNG/ArchUnit/issues/528)でも議論されている。bytecode前提なのでKMP全般には広がりにくい。 | Kotlinの言語構造を第一級オブジェクトとして扱う。top-level declaration、typealias、operator、synthetic callを正確に残す。 |
| [Konsist](https://docs.konsist.lemonappdev.com/writing-tests/architecture-assert) | Kotlinネイティブな宣言APIとKMP対応。テストとしてルールを書きやすい。 | プロダクトの中心は宣言検査/レイヤールールのテストAPIであり、意味解決済みの再利用可能な依存グラフやコールサイト単位の証跡が中心ではない。 | 「ルールを書ける」より先に「正確なグラフがある」ことを差別化する。Konsist的な宣言APIではなく、dependency intelligenceを提供する。 |
| Gradle module graph系プラグイン（例: [vanniktech/gradle-dependency-graph-generator-plugin](https://github.com/vanniktech/gradle-dependency-graph-generator-plugin)） | セットアップが簡単で、モジュール構造の把握に向く。 | 主にbuildscript上のモジュール/外部依存関係で、ソース内のどの宣言が原因か分からない。 | モジュールグラフだけでは勝負しない。モジュール違反の原因となる宣言とコールサイトに降りられることを価値にする。 |
| [Aalekh](https://github.com/shivathapaa/Aalekh) | モジュールアーキテクチャのHTMLレポート、ルール、baseline、メトリクス、SARIFなど運用機能が広い。KMP/Android対応を掲げる。 | 対象はGradle buildが見るモジュール構造であり、Kotlin宣言レベルの意味解決グラフではない。 | UX・レポート・CI出力は市場期待値を示している。klass-treeは同等の運用UXを、より深い宣言レベルの証拠に載せる必要がある。 |
| [Sonargraph](https://www.hello2morrow.com/products/sonargraph/architect) | 商用の成熟した可視化、アーキテクチャDSL、メトリクス、baseline、cycle分析。Kotlin/JVM対応。 | 商用・総合ツールで導入が重い。公式ドキュメントはbytecode中心の解析によりinline関数ではインライン展開先の依存が見えない場合があると説明している（[Kotlin specific issues](https://eclipse.hello2morrow.com/doc/standalone/content/ch04s03s02.html)）。 | 商用級のガバナンス機能を、Kotlinソース意味論に強いオープンなGradleプラグインとして提供する余地がある。 |
| 新興のsemantic graph系（例: [kartograph](https://github.com/ictechgy/kartograph), [ktrics](https://github.com/koji-1009/ktrics)） | コンパイル結果やAnalysis APIを使い、到達性・メトリクス・agent向けクエリに進んでいる。 | 市場がまだ若く、用途もdead code/metrics/reachabilityに分散している。bytecodeベースではKotlinソース上の正確な表現に限界が出やすい。 | 「agent/machine-readable」は将来標準になる。klass-treeもJSONスキーマとクエリAPIを一意な強みとして早期に固めるべき。 |

## 市場に足りないもの

### 1. Kotlin semanticsを壊さない依存解析

Kotlinはtop-level function、extension function、typealias、operator convention、delegation、generated member、inline関数など、bytecodeや単純なsource parsingだけでは意味がずれる構造を多く持つ。Java bytecode中心の解析はJVM上では強い一方、Kotlin固有構造の表現が粗くなりやすい。逆に、importや構文ベースの解析は実際の解決先を取り違えやすい。

klass-treeはKotlin Analysis APIで参照を意味解決し、`TYPE_REFERENCE`、`INHERITANCE`、`CONSTRUCTOR_CALL`、`FUNCTION_CALL`、`PROPERTY_ACCESS`、`CALLABLE_REFERENCE`、`ANNOTATION`、`OPERATOR_OR_SYNTHETIC_CALL`を分けている。これは市場で最も薄い領域であり、継続的に守るべき中核資産である。

### 2. 「どこが悪いか」ではなく「なぜ依存しているか」にたどり着ける証拠

モジュール間やパッケージ間の矢印だけでは、修正担当者は原因調査に戻される。実務では、違反を起こしている宣言、呼び出し種別、file/line/column、経路が必要である。

klass-treeは同じ`(from, to, kind)`をmergeしつつコールサイトを保持している。この証拠レベルは、レポート、PR annotation、影響分析、AI agentによる自動修正の基礎になる。

### 3. レガシーコードベースに導入できる段階的ガバナンス

既存コードで違反が大量にあると、厳格なアーキテクチャルールは初日に失敗する。必要なのは「現状を凍結し、悪化だけ止め、改善を可視化する」ratchetである。

klass-treeのbaselineは行番号ではなくcall-site countを比較するため、無関係な行移動で壊れにくい。これは実運用上の差別化であり、今後のrule engineでも一貫して守るべき体験である。

### 4. オープンでローカル完結し、機械消費しやすい中間表現

商用ツールは高機能だが、データモデルやワークフローが閉じやすい。一方、OSSの単発レポートはカスタム分析やagent連携に弱い。`graph.json`を安定した公開契約にすると、ユーザーは独自可視化、custom rules、コード調査、AIワークフローに再利用できる。

### 5. 大規模リポジトリでの増分解析

宣言レベルのsemantic analysisは重い。klass-treeは現状、実行ごとに選択モジュールを再解析する。大規模monorepoでは、正確さだけでなく変更部分だけを速く再計算できることが採用条件になる。市場にもこれを完全に解決したKotlin特化ツールは少ない。

### 6. Android/KMPをまたぐ一貫モデル

ArchUnitはbytecode前提、module graph系は宣言レベルに届かず、Konsistは宣言検査中心である。KMP/Androidで「プラットフォーム間をまたぐ意味解決済み依存関係」を扱う標準的な解はまだない。ただし、KMPのexpect/actual、source set hierarchy、variant、generated sourceを正確に扱うのは難易度が高い。早期に範囲を広げるより、JVMで精度と運用価値を証明してから拡張すべきである。

## klass-treeが提供できる価値

| 価値 | 現在の根拠 | ユーザー成果 |
| --- | --- | --- |
| Kotlin意味論に忠実な依存グラフ | Kotlin Analysis APIによるsemantic resolution、Kotlin/Java相互参照、typealiasやdelegationなどのテスト群 | 「見えているはずの依存がない」「importベースで誤検出する」を減らす |
| 修正可能な証拠 | edge kindとfile/line/column付きcallSites | 違反報告から直接修正箇所へ移動できる |
| 段階的 modernization | baseline ratchetとdeny rule | レガシー依存を一括解消しなくても悪化を止められる |
| 再利用可能なデータ | platform-independentな`graph.json`、optional DOT | 自社ルール、可視化、agent、CIに組み込める |
| Gradleに自然な導線 | root Gradle plugin、forked analyzer、AOT cache option | ビルド利用者が追加サービスなしで実行できる |

## 推奨ポジショニング

> **klass-treeは、Kotlin/JVMコードベースの依存関係をコンパイラ相当の精度で理解し、修正可能な証拠と段階的なアーキテクチャガバナンスを提供するGradleプラグイン。**

明示的に狙わない領域:

- ktlint/detektのようなstyle/general lint
- Gradle module graphだけの可視化
- 全言語対応の商用アーキテクチャスイート
- サーバー常駐型のcentral analysis platform

## 機能実装ロードマップ

### Phase 0: 配布と計測の土台

| 優先度 | 機能 | 理由 | 実装メモ |
| --- | --- | --- | --- |
| P0 | Gradle Plugin Portal / Maven Central公開 | 現状の`includeBuild`は検証用途には強いが、一般導入では大きな摩擦になる。機能以前の採用 blocker。 | signing、versioning、compatibility matrix、release workflowを整える。 |
| P0 | benchmark fixtureと性能計測 | 大規模対応の判断材料がないと最適化を後回しにしすぎる。 | 小中大 fixture、wall-clock、memory、module数、edge数、unresolved countを記録する。 |
| P0 | schema contractの明文化 | `graph.json`が製品価値の中心なので、追加・後方互換・ID安定性のルールが必要。 | schemaVersion、generator metadata、analysis stats、warningsを明確にする。 |

### Phase 1: グラフを「調べられる」製品にする

| 優先度 | 機能 | 理由 | 実装メモ |
| --- | --- | --- | --- |
| P0 | `graph-query`ライブラリ | HTML report、rule engine、CLI、agent連携の共通基盤。先に作ると重複実装を避けられる。 | `dependenciesOf`、`dependentsOf`、`shortestPath`、`allPaths`、`impactSet`、`cycles`を提供。 |
| P0 | query CLI / Gradle task | ユーザーがJSONを自前で加工しなくてよくする。AI agentにも使いやすい。 | `klassTreeQuery --from ... --to ...`、`--impact`、`--cycles`、JSON/NDJSON出力。 |
| P0 | オフラインHTMLレポート | 生JSON/DOTでは探索できず、Aalekhや商用ツールが示すようにレポート体験は採用に直結する。 | サーバー不要の単一HTML。module/package/classの階層、edge kind filter、検索、方向切替、call-site panel。 |
| P1 | cycle detectionと可視化 | 依存グラフの典型ユースケースで、rulesより先に「構造理解」価値を出せる。 | strongly connected components、cycle group、最小カット候補は後続。 |

### Phase 2: ガバナンスとCI運用

| 優先度 | 機能 | 理由 | 実装メモ |
| --- | --- | --- | --- |
| P1 | architecture rules v1 | 現在のdeny prefixは有用だが、実運用にはlayer/allowlist/例外が必要。 | package/module/layer matcher、allowed dependency、denied dependency、edge kind条件、`because`メッセージ。 |
| P1 | approved exceptions | すべての例外をbaselineに混ぜると意図が失われる。 | 有効期限、owner、reason、対象edge kindを持つexception file。 |
| P1 | SARIF/JUnit出力 | GitHub/GitLabのcode quality UIに乗せられ、CIでの見え方が大きく変わる。 | まずSARIFを優先。callSitesをlocationに写す。 |
| P1 | PR diff summary | 追加/削除依存、増減したcall-site count、新規cycleをレビュー単位で説明する。 | machine-readable diffを先に安定させ、PR commentは薄いadapterにする。 |
| P2 | trend metrics | 改善活動を継続するには時系列が必要。 | dependency count、cycle count、tangle率、denied call-site countをJSON/CSVで出す。DBやSaaSにはしない。 |

### Phase 3: 大規模化

| 優先度 | 機能 | 理由 | 実装メモ |
| --- | --- | --- | --- |
| P1-P2 | モジュール単位の増分解析 | 採用規模を左右する最大の技術投資。 | source/classpath fingerprint、per-module graph cache、依存module変更時のみ再解析。cross-module resolutionの正確性テストが前提。 |
| P2 | 並列解析とメモリ最適化 | forked analyzerは分離できるが、大規模ではコストが高い。 | source set単位並列化、worker上限、heap profile。 |
| P2 | unresolved diagnostics | 精度をユーザーが判断できる必要がある。 | unresolved countをkind/reason/module別に集計し、レポートに出す。 |

### Phase 4: 対象範囲の拡大

| 優先度 | 機能 | 理由 | 実装メモ |
| --- | --- | --- | --- |
| P2 | Android/AGP variant対応 | Kotlin市場ではAndroidが大きい。ただしvariantとgenerated sourceの扱いが必要。 | JVMでquery/report/rulesが安定した後。variantごとのsource setとclasspathをAnalysisSpecへ写す。 |
| P3 | KMP source set対応 | 市場ギャップは大きいが複雑度も最大。 | commonizer/expect/actual/platform classpathの調査から始める。最初はcommonMainのみでもよい。 |
| P3 | IDE plugin | フィードバックループを最短化できるが、Gradle plugin本体より保守コストが高い。 | HTML reportとquery CLIの需要を確認してから検討する。 |
| P3 | external dependency nodes | ライブラリ依存の可視化需要はあるが、ノード爆発とノイズを招く。 | default off、artifact単位集約、必要時のみ展開。 |

## アーキテクチャ上の指針

1. **`klass-tree-graph-core`を公開契約にする。** schemaの後方互換性、ID安定性、serialization testを強化する。
2. **`graph-query`を新設し、report/rules/CLIの共通土台にする。** Gradle pluginはorchestrationに留める。
3. **analyzerの正確性を最優先で守る。** top-level、typealias、delegation、Java interop、generated sourceのregression corpusを増やす。精度低下はすべての上位機能を無効化する。
4. **レポートはstatic/offline-first。** サーバーや外部サービスを必須にしない。データはJSONでエクスポートできる。
5. **rule engineは「絶対禁止」と「ratchet」の両方を扱う。** 新規コードには厳格、既存コードにはbaseline、例外には期限とownerを持たせる。
6. **machine-readable first, human-readable second。** SARIF/JSON/NDJSONを安定させ、その上にHTMLとPR commentを作る。

## 成功指標

- 初回導入から`graph.json`生成まで10分以内。
- example以外の中規模OSSプロジェクトで、unresolved reference率とanalysis errorを継続計測できる。
- HTML reportから任意の違反について、3クリック以内で少なくとも1つのcall siteへ到達できる。
- CI違反にfile/line/columnとrule reasonが表示される。
- 大規模fixtureで、変更が1モジュールの場合の分析時間がfull analysisより有意に短い。
- baseline更新なしで、無関係な行移動により`klassTreeCheck`が失敗しない。

## 最初に切るべき実装チケット

1. **Publish release pipeline**: Plugin Portal/Maven Central公開、署名、versioned docs。
2. **Document graph schema v1 contract**: ID、nullable field、互換性方針、stats拡張。
3. **Add graph-query core**: neighbor、path、impact、SCC/cycle API。
4. **Add query CLI/Gradle task**: JSON/NDJSON出力を持つ最小CLI。
5. **Generate static HTML report MVP**: 検索、edge kind filter、call-site panel。
6. **Emit SARIF from klassTreeCheck**: deny violationをSARIF locationへ写す。
7. **Add benchmark harness**: fixture生成、計測結果JSON、CI regression threshold。

## 主要リスクと対策

| リスク | 影響 | 対策 |
| --- | --- | --- |
| Kotlin Analysis APIのversion coupling | 利用者のKotlin version更新で壊れる | compatibility matrix、複数Kotlin versionのCI、明示的なerror message。 |
| 機能範囲の拡大 | lint/可視化/商用architecture suiteとの中途半端な競争になる | 「semantic dependency intelligence」以外のscopeを明示的に削る。 |
| 大規模解析コスト | 採用対象が小規模プロジェクトに限定される | benchmarkを先に作り、増分解析をPhase 3ではなく採用状況に応じて前倒しする。 |
| KMP対応の過大評価 | 複雑なsource set semanticsに開発資源を食われる | Android/AGP variantから始め、KMPは調査スパイクと限定対応に分ける。 |
| HTML reportの保守コスト | frontend実装が本体より重くなる | サーバーなし単一HTML、依存最小化、graph-queryのJSON contractに集中する。 |

## 参考

- [ArchUnit User Guide](https://www.archunit.org/userguide/html/000_Index.html)
- [ArchUnit issue: Support for Kotlin toplevel functions](https://github.com/TNG/ArchUnit/issues/528)
- [Konsist Architecture Assertion](https://docs.konsist.lemonappdev.com/writing-tests/architecture-assert)
- [Aalekh](https://github.com/shivathapaa/Aalekh)
- [Sonargraph Architect](https://www.hello2morrow.com/products/sonargraph/architect)
- [Sonargraph Kotlin specific issues](https://eclipse.hello2morrow.com/doc/standalone/content/ch04s03s02.html)
- [kartograph](https://github.com/ictechgy/kartograph)
- [ktrics](https://github.com/koji-1009/ktrics)
