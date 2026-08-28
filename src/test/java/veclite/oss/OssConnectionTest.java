package veclite.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.Bucket;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 阿里云 OSS 连接 / 上传 / 下载 / 删除 联通性测试。
 *
 * <p>所有凭据通过环境变量读取,避免硬编码进仓库:
 * <ul>
 *   <li>{@code ALIYUN_OSS_ENDPOINT}            e.g. {@code oss-cn-beijing.aliyuncs.com}</li>
 *   <li>{@code ALIYUN_OSS_ACCESS_KEY_ID}</li>
 *   <li>{@code ALIYUN_OSS_ACCESS_KEY_SECRET}</li>
 *   <li>{@code ALIYUN_OSS_BUCKET}              e.g. {@code java-lhy}</li>
 * </ul>
 *
 * <p>本地运行示例(PowerShell):
 * <pre>
 * $env:ALIYUN_OSS_ENDPOINT          = "oss-cn-beijing.aliyuncs.com"
 * $env:ALIYUN_OSS_ACCESS_KEY_ID     = "&lt;your-key-id&gt;"
 * $env:ALIYUN_OSS_ACCESS_KEY_SECRET = "&lt;your-key-secret&gt;"
 * $env:ALIYUN_OSS_BUCKET            = "java-lhy"
 * ./gradlew test --tests veclite.oss.OssConnectionTest
 * </pre>
 *
 * <p>任一环境变量未设置时,所有用例 {@code assumeTrue} 跳过,CI 上不会因为缺凭据而红。
 */
public class OssConnectionTest {

    private static final String ENDPOINT = System.getenv("ALIYUN_OSS_ENDPOINT");
    private static final String ACCESS_KEY = System.getenv("ALIYUN_OSS_ACCESS_KEY_ID");
    private static final String SECRET_KEY = System.getenv("ALIYUN_OSS_ACCESS_KEY_SECRET");
    private static final String BUCKET = System.getenv("ALIYUN_OSS_BUCKET");

    private static void requireEnv() {
        Assumptions.assumeTrue(
                ENDPOINT != null && !ENDPOINT.isBlank()
                        && ACCESS_KEY != null && !ACCESS_KEY.isBlank()
                        && SECRET_KEY != null && !SECRET_KEY.isBlank()
                        && BUCKET != null && !BUCKET.isBlank(),
                "跳过:未设置 ALIYUN_OSS_* 环境变量(见类注释)");
    }

    @Test
    public void testListBuckets() {
        requireEnv();
        OSS oss = new OSSClientBuilder().build(ENDPOINT, ACCESS_KEY, SECRET_KEY);
        try {
            List<Bucket> buckets = oss.listBuckets();
            System.out.println("✓ 连接成功!Bucket 列表:");
            for (Bucket b : buckets) {
                System.out.println("  - " + b.getName() + " (" + b.getLocation() + ")");
            }
        } finally {
            oss.shutdown();
        }
    }

    @Test
    public void testUploadAndDownload() {
        requireEnv();
        OSS oss = new OSSClientBuilder().build(ENDPOINT, ACCESS_KEY, SECRET_KEY);
        try {
            String key = "veclite/connect-test/hello.txt";
            String content = "Hello from veclite! time=" + System.currentTimeMillis();

            oss.putObject(BUCKET, key,
                    new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            System.out.println("✓ 上传成功: " + key);

            String downloaded = new String(oss.getObject(BUCKET, key)
                    .getObjectContent()
                    .readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("✓ 下载成功: " + downloaded);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            oss.shutdown();
        }
    }

    @Test
    public void testDeleteObject() {
        requireEnv();
        OSS oss = new OSSClientBuilder().build(ENDPOINT, ACCESS_KEY, SECRET_KEY);
        try {
            String key = "veclite/connect-test/hello.txt";
            oss.deleteObject(BUCKET, key);
            System.out.println("✓ 删除成功: " + key);
        } finally {
            oss.shutdown();
        }
    }
}
