# charset-study

文字コードを学習するための Java プロジェクト。

## 必要なもの

- JDK 25
- Maven

## 実行

```sh
mvn -q compile exec:java                          # 既定の文字列 "Aあ漢🍣" を調べる
mvn -q compile exec:java -Dexec.args="ｱ①髙"       # 任意の文字列を調べる
mvn -q compile exec:java -Dexec.mainClass=study.charset.FileDemo   # 文字コード別にファイルを書いて読み比べる
mvn -q compile exec:java -Dexec.mainClass=study.charset.ReadDemo   # samples/ のファイルを文字コード別に読み比べる
mvn -q compile exec:java -Dexec.mainClass=study.charset.ReadDemo -Dexec.args="ファイルかディレクトリ"
mvn -q compile exec:java -Dexec.mainClass=study.charset.MappingTable   # doc/charset-mapping.html を生成する
mvn test                                          # 性質を確かめるテスト
```

## 構成

- `Main.java` — 文字列のコードポイントと、文字コード別のバイト列・往復の可否・文字化けを表示する
- `FileDemo.java` — 文字列を文字コード別に `target/files/` へ書き、別の文字コードで読み比べる
- `ReadDemo.java` — 既存のファイルを文字コード別に厳密にデコードし、矛盾なく読めるかを判定する
- `MappingTable.java` — 日本語の文字コード対応表（Shift_JIS・windows-31j・区点・UTF-8・Unicode・MJ+）を JDK の Charset から生成する
- `doc/charset-mapping.html` — 生成した対応表
- `Encodings.java` — 16 進表記・文字化け再現・厳密なデコードの小道具
- `samples/` — 文字コード別のサンプルファイル。自分で作ったファイルを置いてもよい
- `EncodingsTest.java` — 調べた性質をテストとして残す場所
