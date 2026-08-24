# Veclite Console

零构建依赖的向量数据管理前端。它通过浏览器本地存储保存多个项目的 Veclite 管理 API 地址，因此可在同一个页面切换管理不同 Spring Boot 项目中的向量库。

## 启动

终端 1：启动示例后端（需要 Java 17）。

```sh
cd /Users/zhaoyuanlu/dev/veclite
./gradlew publishToMavenLocal
cd examples/springboot-vector-admin
../../gradlew bootRun
```

终端 2：启动前端静态服务器。

```sh
cd /Users/zhaoyuanlu/dev/veclite/web
python3 -m http.server 5173
```

打开 `http://localhost:5173`，添加项目时填写目标服务的 API 地址，例如：

```text
http://localhost:8080/veclite/api/v1
```

目标项目需要引入 Veclite SDK 并配置：

```yaml
veclite:
  web:
    enabled: true
    allowed-origins: http://localhost:5173
```

管理端支持：项目切换、向量库数据源配置（默认本地，也可填写兼容管理 API 的 HTTP 地址）、Embedding 数据源配置（后端托管、独立 HTTP 服务或关闭）、向量库创建/删除、统计查看、文档写入、文档分页浏览、向量/文本检索、检索结果删除与持久化刷新。选择独立 HTTP Embedding 服务时，前端会按 `{model, version, input}` 调用服务，并将结果提交到向量库检索；该服务需允许 `http://localhost:5173` 跨域。
