# 代码与 API 规范

- Java 17，四空格缩进；类 `PascalCase`，方法/字段 `camelCase`，常量 `UPPER_SNAKE_CASE`。
- 方法只完成一个动作；复杂流程拆成命名明确的私有方法；消除魔法值。
- 优先不可变对象和 `record`；枚举分派优先使用 switch 表达式。
- public 类、构造器和方法提供 JavaDoc，说明参数、返回值、异常和兼容性。
- 公共入口显式校验 null、空向量、维度、Top-K、ID、容量和不支持的组合，并尽早失败。
- 查询入口预计算 query norm、query sum 和度量类型；热循环禁止临时对象、装箱和字符串。
- 优先连续数组、Direct Buffer、BitSet；线程池必须有界，资源必须及时释放。
- 不得无理由改变已有签名、默认值、异常类型、序列化格式或线程语义。
