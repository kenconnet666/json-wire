# 手动发布

发布由维护者在本机手动执行，CI 不发布。

## 发布的内容

| 去处 | 构件 |
| --- | --- |
| Maven 中央仓库 | `org.dpdns.zerodep:json-wire-parent`（POM，`json-wire` 的父 POM，必须一起发布）、`org.dpdns.zerodep:json-wire`（jar、sources、javadoc） |
| npm | `@zerodep-dev/json-wire`（`dist` 与 `src`） |

jar 的清单里写了 `Automatic-Module-Name: org.dpdns.zerodep.wire`，在模块路径上也能使用。两边的版本号一起变：Maven 的 `0.1.0` 对应 npm 的 `0.1.0`；开发中分别是 `0.1.0-SNAPSHOT` 与 `0.1.0-dev`。

## 每次发布前

1. 根 `pom.xml` 与 `java/json-wire/pom.xml` 的版本去掉 `-SNAPSHOT`，`typescript/packages/json-wire/package.json` 的版本去掉 `-dev`。
2. 要发布的提交已推到 `master`，GitHub Actions 全部通过。
3. 本机预检（不签名、不上传）：

   ```powershell
   .\mvnw.cmd --batch-mode -P release '-Dgpg.skip' verify
   cd typescript; pnpm install --frozen-lockfile; pnpm test
   cd packages/json-wire; pnpm pack --dry-run   # 核对包里只有 dist、src、README、package.json
   ```

## Maven 中央仓库

一次性准备（命名空间 `org.dpdns.zerodep` 已验证、GPG 密钥、Central Portal 令牌）与 query 仓库相同，见它的 `docs/release.md`。令牌在 `~/.m2/settings.xml` 里通过环境变量引用（`<id>central</id>`），不写明文。

```powershell
$env:CENTRAL_TOKEN_USERNAME = '<令牌用户名>'
$env:CENTRAL_TOKEN_PASSWORD = '<令牌密码>'
.\mvnw.cmd --batch-mode -P release "-Dgpg.executable=C:\Program Files\Git\usr\bin\gpg.exe" deploy
```

插件把父 POM 与 jar 打成一个包上传，等到 Portal 校验完成（`waitUntil=validated`）；然后在 <https://central.sonatype.com/publishing/deployments> 核对文件清单并点 Publish。已发布的版本不能删除或覆盖。

## npm

```powershell
cd typescript/packages/json-wire
pnpm login                      # 或用 npm 的访问令牌，令牌放在用户级 .npmrc 里，不进仓库
pnpm publish --access public    # 发布前会先跑 prepack，确保 dist 是最新的
```

`@zerodep-dev` 作用域属于维护者的 npm 账号；预发布版本用 `pnpm publish --tag next`，免得成为 `latest`。

## 发布后

```powershell
git tag -a v0.1.0 -m "0.1.0"
git push origin v0.1.0
```

然后把版本改成下一个 `-SNAPSHOT` / `-dev`。
