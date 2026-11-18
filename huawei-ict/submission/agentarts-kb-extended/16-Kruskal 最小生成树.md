# Kruskal：按边贪心与并查集

> 课程：data_structures_algorithms（ID 1）  
> 知识点：Kruskal 最小生成树（ID 9313）  
> 难度：medium  
> 来源：https://github.com/cp-algorithms/cp-algorithms/blob/4eba0d6abffca2dfbb41e4cd74a8bb9e453fb42b/src/graph/mst_kruskal_with_dsu.md  
> 固定 commit：4eba0d6abffca2dfbb41e4cd74a8bb9e453fb42b  
> 许可证：CC-BY-SA-4.0  
> 归属：Adapted from cp-algorithms contributors  
> 检索别名：Kruskal 最小生成树  
> 说明：本文件为华为 ICT 大赛演示用课程证据，改编自上述开源资料；未改变原项目归属。

## 中文教学导读
Kruskal 将无向图所有边按权值升序扫描，只选择连接两个不同连通分量的边。割性质保证跨越任意割的最轻安全边可以加入某棵最小生成树。并查集负责快速判断是否成环，整体复杂度主要来自排序，为 O(E log E)。

## 结果校验
连通图最终应选 V-1 条边；不足说明原图不连通，此时得到的是最小生成森林。相同权值边可能产生多棵不同但总权相同的最小生成树。

## 学习目标
- 解释 Kruskal 最小生成树 的适用前提、核心不变式和终止条件。
- 能够在小规模输入上逐步追踪关键数据结构或状态转移，而不是只背模板。
- 能够给出时间、空间复杂度，并在约束变化时与相邻方案进行选型。

## 先修与关联知识
- 建议先修：Bellman-Ford、Floyd-Warshall
- 后续关联：Prim 最小生成树、强连通分量
- 检索别名：Kruskal 最小生成树、Kruskal：按边贪心与并查集
- 建议难度：medium；建议先独立推演，再查看上游原文核验。

## 逐步推演案例
按权排序五条边，使用并查集逐条判断是否加入，最终核对所选边数是否为 V-1。

推演时依次完成：①写清输入与约束；②列出每一步关键状态；③标记不变式或架构承诺；④核对输出与边界；⑤改变一个条件，观察原方法何处失效。

## 方法选择与工程检查
- 先确认输入规模、数据范围、图的方向/权值或操作是否在线，再选择算法。
- 实现后用空输入、单元素、重复值、极值和不满足前提的反例检查边界。
- 复杂度必须包含预处理、单次操作与全部操作，均摊复杂度要明确适用条件。

## 自测题与答案框架
1. 为什么该主题中的核心步骤是正确的？请用不变式、状态转移或架构契约回答。
2. 若移除一个关键前提，应继续使用 Kruskal 最小生成树、修改实现，还是切换方案？说明代价。
3. 为上述推演案例设计一个最容易暴露错误的边界输入，并写出期望状态序列。

答案框架：写出不变式或状态定义，按步骤展示状态变化，最后用前提与复杂度解释结论。引用结论时应能定位到下方固定版本的开源证据。

## 开源来源与许可
- 上游项目：cp-algorithms
- 固定版本：4eba0d6abffca2dfbb41e4cd74a8bb9e453fb42b
- 原文链接：https://github.com/cp-algorithms/cp-algorithms/blob/4eba0d6abffca2dfbb41e4cd74a8bb9e453fb42b/src/graph/mst_kruskal_with_dsu.md
- 开源许可：CC-BY-SA-4.0（https://creativecommons.org/licenses/by-sa/4.0/）
- 适配说明：EduPath 增加中文教学导读并截取上游原文；未改变原项目归属，衍生内容沿用对应上游许可。
