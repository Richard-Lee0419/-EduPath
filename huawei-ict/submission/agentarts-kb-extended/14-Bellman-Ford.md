# Bellman-Ford：负权边与负环

> 课程：data_structures_algorithms（ID 1）  
> 知识点：Bellman-Ford（ID 9311）  
> 难度：advanced  
> 来源：https://github.com/cp-algorithms/cp-algorithms/blob/4eba0d6abffca2dfbb41e4cd74a8bb9e453fb42b/src/graph/bellman_ford.md  
> 固定 commit：4eba0d6abffca2dfbb41e4cd74a8bb9e453fb42b  
> 许可证：CC-BY-SA-4.0  
> 归属：Adapted from cp-algorithms contributors  
> 检索别名：Bellman-Ford  
> 说明：本文件为华为 ICT 大赛演示用课程证据，改编自上述开源资料；未改变原项目归属。

## 中文教学导读
Bellman-Ford 重复扫描所有边并执行松弛。若不存在从源点可达的负环，任意最短简单路径至多包含 V-1 条边，因此 V-1 轮后距离稳定。第 V 轮仍能松弛说明存在可达负环；沿 parent 回退 V 次可进入环内并恢复负环。

## 复杂度与优化
时间 O(VE)、空间 O(V)，适合有负权或需要负环检测的图。若某轮没有更新可提前结束。松弛前必须确认起点距离不是无穷，避免溢出或伪更新。

## 学习目标
- 解释 Bellman-Ford 的适用前提、核心不变式和终止条件。
- 能够在小规模输入上逐步追踪关键数据结构或状态转移，而不是只背模板。
- 能够给出时间、空间复杂度，并在约束变化时与相邻方案进行选型。

## 先修与关联知识
- 建议先修：拓扑排序、Dijkstra 最短路
- 后续关联：Floyd-Warshall、Kruskal 最小生成树
- 检索别名：Bellman-Ford、Bellman-Ford：负权边与负环
- 建议难度：advanced；建议先独立推演，再查看上游原文核验。

## 逐步推演案例
在含一条负边与一个可达负环的图上执行第 1 到 V 轮松弛，指出第 V 轮更新如何成为负环证据。

推演时依次完成：①写清输入与约束；②列出每一步关键状态；③标记不变式或架构承诺；④核对输出与边界；⑤改变一个条件，观察原方法何处失效。

## 方法选择与工程检查
- 先确认输入规模、数据范围、图的方向/权值或操作是否在线，再选择算法。
- 实现后用空输入、单元素、重复值、极值和不满足前提的反例检查边界。
- 复杂度必须包含预处理、单次操作与全部操作，均摊复杂度要明确适用条件。

## 自测题与答案框架
1. 为什么该主题中的核心步骤是正确的？请用不变式、状态转移或架构契约回答。
2. 若移除一个关键前提，应继续使用 Bellman-Ford、修改实现，还是切换方案？说明代价。
3. 为上述推演案例设计一个最容易暴露错误的边界输入，并写出期望状态序列。

答案框架：写出不变式或状态定义，按步骤展示状态变化，最后用前提与复杂度解释结论。引用结论时应能定位到下方固定版本的开源证据。

## 开源来源与许可
- 上游项目：cp-algorithms
- 固定版本：4eba0d6abffca2dfbb41e4cd74a8bb9e453fb42b
- 原文链接：https://github.com/cp-algorithms/cp-algorithms/blob/4eba0d6abffca2dfbb41e4cd74a8bb9e453fb42b/src/graph/bellman_ford.md
- 开源许可：CC-BY-SA-4.0（https://creativecommons.org/licenses/by-sa/4.0/）
- 适配说明：EduPath 增加中文教学导读并截取上游原文；未改变原项目归属，衍生内容沿用对应上游许可。
