# Floyd-Warshall：全源最短路与动态规划

> 课程：data_structures_algorithms（ID 1）  
> 知识点：Floyd-Warshall（ID 9312）  
> 难度：advanced  
> 来源：https://github.com/cp-algorithms/cp-algorithms/blob/4eba0d6abffca2dfbb41e4cd74a8bb9e453fb42b/src/graph/all-pair-shortest-path-floyd-warshall.md  
> 固定 commit：4eba0d6abffca2dfbb41e4cd74a8bb9e453fb42b  
> 许可证：CC-BY-SA-4.0  
> 归属：Adapted from cp-algorithms contributors  
> 检索别名：Floyd-Warshall  
> 说明：本文件为华为 ICT 大赛演示用课程证据，改编自上述开源资料；未改变原项目归属。

## 中文教学导读
Floyd-Warshall 的状态 d[i][j] 表示当前允许一组中间节点时 i 到 j 的最短距离。加入中间点 k 后执行 d[i][j]=min(d[i][j],d[i][k]+d[k][j])。三重循环复杂度 O(V^3)，空间 O(V^2)，适合点数较小且需要任意两点查询的场景。

## 负环判断
算法结束后若 d[v][v]<0，则 v 位于或可达某个负环。计算前应把对角线置 0，并在相加前检查两个子距离都不是无穷。

## 学习目标
- 解释 Floyd-Warshall 的适用前提、核心不变式和终止条件。
- 能够在小规模输入上逐步追踪关键数据结构或状态转移，而不是只背模板。
- 能够给出时间、空间复杂度，并在约束变化时与相邻方案进行选型。

## 先修与关联知识
- 建议先修：Dijkstra 最短路、Bellman-Ford
- 后续关联：Kruskal 最小生成树、Prim 最小生成树
- 检索别名：Floyd-Warshall、Floyd-Warshall：全源最短路与动态规划
- 建议难度：advanced；建议先独立推演，再查看上游原文核验。

## 逐步推演案例
用三个节点的距离矩阵演示 k 从 1 到 3 的更新，检查 d[i][k] 与 d[k][j] 为无穷时为何不能直接相加。

推演时依次完成：①写清输入与约束；②列出每一步关键状态；③标记不变式或架构承诺；④核对输出与边界；⑤改变一个条件，观察原方法何处失效。

## 方法选择与工程检查
- 先确认输入规模、数据范围、图的方向/权值或操作是否在线，再选择算法。
- 实现后用空输入、单元素、重复值、极值和不满足前提的反例检查边界。
- 复杂度必须包含预处理、单次操作与全部操作，均摊复杂度要明确适用条件。

## 自测题与答案框架
1. 为什么该主题中的核心步骤是正确的？请用不变式、状态转移或架构契约回答。
2. 若移除一个关键前提，应继续使用 Floyd-Warshall、修改实现，还是切换方案？说明代价。
3. 为上述推演案例设计一个最容易暴露错误的边界输入，并写出期望状态序列。

答案框架：写出不变式或状态定义，按步骤展示状态变化，最后用前提与复杂度解释结论。引用结论时应能定位到下方固定版本的开源证据。

## 开源来源与许可
- 上游项目：cp-algorithms
- 固定版本：4eba0d6abffca2dfbb41e4cd74a8bb9e453fb42b
- 原文链接：https://github.com/cp-algorithms/cp-algorithms/blob/4eba0d6abffca2dfbb41e4cd74a8bb9e453fb42b/src/graph/all-pair-shortest-path-floyd-warshall.md
- 开源许可：CC-BY-SA-4.0（https://creativecommons.org/licenses/by-sa/4.0/）
- 适配说明：EduPath 增加中文教学导读并截取上游原文；未改变原项目归属，衍生内容沿用对应上游许可。
