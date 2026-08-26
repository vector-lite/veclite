# VecLite View - 向量数据可视化管理与 Debug 平台

VecLite View 是为 [VecLite](https://github.com/erictowns/veclite) 嵌入式/分布式高性能向量检索引擎定制的可视化管理前端服务。

---

## 🌟 核心特性

1. **向量库全生命周期管理 (Vector Stores)**
   - 实时监控各个 Store 的维度、容量、度量方式 (COSINE / L2 / DOT)、量化模式 (SQ8 / NONE)。
   - 快捷创建库、删库 (Drop)、快照手动持久化落盘 (Flush) 与从磁盘重载 (Reload)。

2. **向量数据可视化 CRUD (Data Management)**
   - **向量脱敏与屏蔽（特色）**：默认屏蔽原始高维浮点数组（防止页面卡顿与泄露），显示 L2 范数、微缩特征谱条与维度标签；支持一键展开/全屏图表分析/复制。
   - **增删改查**：单条插入（支持随机向量生成器）、批量 JSON/JSONL 导入、元数据可视化键值编辑器、按 ID / 条件 (Delete by Filter) 批量删除。

3. **向量查询 Debug 可视化工作台 (Search Debugger)**
   - **多模态检索**：支持文本检索 (`/search/text`)、向量检索 (`/search/vector`)、混合检索 (`/search/hybrid`)。
   - **检索耗时与指标分析**：精确展示后端执行耗时 (ms)、召回总数、最高/最低得分。
   - **相似度得分衰减图谱**：直观展示 Top-K 得分梯度。
   - **高维特征共鸣热力图**：可视化对比 Query 向量与匹配文档向量在各维度的数值积分与特征共鸣。
   - **API 报文与 SDK 代码生成**：一键导出 cURL 与调用 JSON 报文。

4. **一键生成演示测试数据 (Demo Generator)**
   - 在「系统设置」中可一键自动创建 `demo_knowledge_base` 库并写入 20 条模拟 RAG 知识库与向量数据。

---

## 🚀 启动指南

### 1. 安装依赖 (已预装)
```bash
npm install
```

### 2. 启动前端服务
```bash
npm run dev
# 或在 Windows 双击运行 start.bat
```
启动后访问：`http://localhost:5173`

### 3. 连接 VecLite 后端
在页面顶部或「系统设置」中配置后端地址（默认 `http://localhost:8080/veclite/api/v1`）。
