package veclite.config;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 允许 URL 路径中保留编码后的斜杠（%2F），把模型名等含 "/" 的字段传进 Spring 路由。
 * <p>
 * 背景：Tomcat 9 默认对 "%2F" 走 strict 校验直接 400，导致
 * {@code POST /embedding/models/qllama/bge-small-zh-v1.5/embed} 这类
 * 路径里的 "/" 无法被 PathVariable 解析为模型名的一部分；
 * 这里将 {@code encodedSolidusHandling} 设为 "pass"，
 * 让 request URI 透传给 Spring，由业务层自行决定是否解码。
 * <p>
 * 安全权衡：放行 %2F 等价于信任所有调用方不会利用它绕过路径校验；
 * veclite 当前为内嵌 SDK，部署边界由调用方控制，可接受此权衡。
 * <p>
 * 关闭开关：veclite.web.allow-encoded-slash=false
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(Connector.class)
@ConditionalOnProperty(name = "veclite.web.allow-encoded-slash", havingValue = "true", matchIfMissing = true)
public class TomcatEncodedSlashCustomizer {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> allowEncodedSlashCustomizer() {
        // Tomcat 9.0.83 的 EncodedSolidusHandling.fromString 接受小写 "decode" / "reject" / "passthrough"。
        // 我们要的是 passthrough：让 %2F 保持编码状态透传到 Spring，{name} 才能收到含 "/" 的模型名。
        return factory -> factory.addConnectorCustomizers(connector -> connector.setEncodedSolidusHandling("passthrough"));
    }
}
