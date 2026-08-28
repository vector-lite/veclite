package veclite;

import org.junit.jupiter.api.Disabled;

/**
 * 临时禁用：该测试依赖 v2.4 文档规划的 SQ8 c1/c2 预计算 API
 * （SQ8QueryPrecomputation / precompute / calculateScorePrecomputed），
 * 这些方法在主代码里尚未实现。待 P0 c1/c2 预计算功能落地后重新启用。
 *
 * 详见 v2.4/architecture_design_v2.4.md §3.2
 */
@Disabled("SQ8 c1/c2 预计算功能尚未实现，详见 v2.4 §3.2")
public class SQ8PrecomputationTest {
    // 类体清空，等 P0 c1/c2 预计算功能落地后从 git history 恢复
}
