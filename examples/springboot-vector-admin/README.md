# Veclite Spring Boot 管理示例

此服务开启 Veclite SDK 的管理 API，供仓库根目录 `web/` 管理前端连接。

```sh
# 在仓库根目录执行一次
./gradlew publishToMavenLocal

cd examples/springboot-vector-admin
../../gradlew bootRun
```

默认 API 地址为 `http://localhost:8080/veclite/api/v1`。生产环境请把 `veclite.web.allowed-origins` 改为管理前端的实际来源。
