#!/usr/bin/env python3
"""Build a license-traceable seed corpus from checked-out open-source docs.

The generated JSONL is intentionally small enough for the competition server:
each topic contains an original Chinese teaching guide plus a short upstream
excerpt pinned to a concrete commit.  The complete attribution and license are
embedded in every document so they survive semantic chunking and retrieval.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import time
from collections import Counter
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass(frozen=True)
class OpenSourceTopic:
    course_id: int
    course_code: str
    knowledge_point_id: int
    knowledge_point: str
    title: str
    project: str
    repo: str
    source_path: str
    license: str
    license_url: str
    chinese_guide: str


CP_REPO = "https://github.com/cp-algorithms/cp-algorithms"
RISCV_REPO = "https://github.com/riscv/riscv-isa-manual"
IBEX_REPO = "https://github.com/lowRISC/ibex"
OPENDSA_REPO = "https://github.com/OpenDSA/OpenDSA"


TOPICS: tuple[OpenSourceTopic, ...] = (
    OpenSourceTopic(1, "data_structures_algorithms", 9301, "二分查找", "二分查找：不变式、边界与答案二分", "cp-algorithms", CP_REPO, "src/num_methods/binary_search.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
二分查找的前提不是“数组必须排序”这一句口号，而是判定函数在搜索区间上具有单调性。维护左闭右开区间 [l, r) 时，每轮必须保证答案仍在区间内；根据 mid 是否满足条件收缩一侧，直到区间只剩一个候选。精确值查找复杂度为 O(log n)，答案二分则把优化问题改写为“给定阈值是否可行”的判定问题。

## 易错边界
- mid 应避免 l+r 溢出，可写成 l+(r-l)//2。
- 明确寻找的是第一个满足、最后一个满足，还是任意满足的位置。
- 浮点二分应使用固定迭代次数或误差阈值，不能直接套用整数终止条件。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9302, "栈、队列与单调结构", "栈、队列、单调队列与最小栈", "cp-algorithms", CP_REPO, "src/data_structures/stack_queue_modification.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
栈按后进先出组织状态，适合括号匹配、表达式求值和 DFS；队列按先进先出扩展层次，是 BFS 的基础。最小栈可为每个元素同步保存“入栈后的当前最小值”，使查询最小值保持 O(1)。单调队列在窗口移动时从队尾删除不可能成为最优解的元素，再从队首移除过期下标，可在线性时间求所有滑动窗口最值。

## 复杂度与不变式
每个元素至多入队一次、出队一次，因此单调队列总复杂度 O(n)。关键不变式是队列中的下标递增，而对应值保持单调。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9303, "并查集", "并查集：路径压缩与按秩合并", "cp-algorithms", CP_REPO, "src/data_structures/disjoint_set_union.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
并查集维护若干互不相交集合，核心操作是 find 查找代表元和 union 合并集合。路径压缩让查找经过的节点直接连接到代表元；按大小或按秩合并让较浅的树挂到较深的树下。两种优化同时使用时，m 次操作的均摊复杂度接近 O(m α(n))，其中 α 是增长极慢的反阿克曼函数。

## 典型应用
无向图连通性、Kruskal 最小生成树、离线动态连通和等价关系合并。并查集不擅长直接支持删除；需要删除时通常采用离线逆序、回滚并查集或其他数据结构。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9304, "树状数组", "树状数组：前缀和与 lowbit", "cp-algorithms", CP_REPO, "src/data_structures/fenwick.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
树状数组利用 lowbit(x)=x&(-x) 划分前缀区间，支持单点修改和前缀查询。更新时不断令 i += lowbit(i)，查询时不断令 i -= lowbit(i)。两种操作都是 O(log n)，空间 O(n)。区间和可由 prefix(r)-prefix(l-1) 得到；通过差分还可扩展为区间修改、单点查询，或使用两个树状数组实现区间修改、区间求和。

## 易错点
经典实现使用 1-based 下标；0-based 写法的位运算不同。建树、查询和更新必须采用同一套下标约定。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9305, "线段树", "线段树：区间聚合与懒标记", "cp-algorithms", CP_REPO, "src/data_structures/segment_tree.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
线段树递归地把区间二分，每个节点保存子区间的可合并信息，如和、最值或最大子段和。建树 O(n)，单点修改和区间查询 O(log n)，常用数组实现占约 4n 空间。区间修改使用懒标记：整段被覆盖时先记录待下传操作，只在访问子节点前 push，从而保持 O(log n) 更新。

## 设计检查
先定义节点信息和 merge，再定义修改如何作用于节点、多个标记如何合成。若这三者不满足封闭性，就不能直接套用线段树模板。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9306, "稀疏表", "稀疏表：静态 RMQ 与幂区间", "cp-algorithms", CP_REPO, "src/data_structures/sparse-table.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
稀疏表适合数组不再修改的静态区间查询。st[k][i] 保存从 i 开始、长度 2^k 的区间结果，预处理 O(n log n)。对 min、max、gcd 这类幂等运算，查询 [l,r] 时取两个长度 2^k 的重叠区间即可 O(1) 得到答案；普通求和不具备幂等性，需要拆成不重叠幂区间，查询为 O(log n)。

## 选择原则
存在在线更新时优先考虑线段树或树状数组；只读且查询很多时，稀疏表以更多预处理换取极快查询。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9307, "广度优先搜索", "BFS：分层遍历与无权最短路", "cp-algorithms", CP_REPO, "src/graph/breadth-first-search.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
BFS 从起点按距离分层扩展，队列保证先发现的节点具有更短的边数距离。在无权图或所有边权相同的图中，节点第一次被发现时即可确定最短距离。记录 parent 可恢复路径；多源 BFS 则把所有源点以距离 0 同时入队。

## 复杂度与变体
邻接表实现为 O(V+E)。边权仅为 0 或 1 时使用双端队列：0 权边从队首加入，1 权边从队尾加入，得到 0-1 BFS。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9308, "深度优先搜索", "DFS：时间戳、边分类与递归栈", "cp-algorithms", CP_REPO, "src/graph/depth-first-search.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
DFS 沿一条路径深入后回溯，适合连通分量、拓扑关系、环检测与树上递归。为节点记录进入时间 tin 和退出时间 tout，可判断祖先关系。三色标记中白色表示未访问、灰色表示仍在递归栈、黑色表示完成；有向图遇到指向灰色节点的边说明存在环。

## 工程注意
邻接表复杂度 O(V+E)。深图可能导致递归栈溢出，应改为显式栈或提高栈空间；无向图遍历还需忽略通向父节点的反向边。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9309, "拓扑排序", "拓扑排序：偏序、入度与环检测", "cp-algorithms", CP_REPO, "src/graph/topological-sort.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
拓扑序只存在于有向无环图。Kahn 算法维护入度为 0 的队列，每取出一个节点就删除其出边；若最终输出节点数少于 V，则图中有环。DFS 后序逆序也能得到拓扑序，但必须配合颜色标记检测回边。

## 应用与判定
课程依赖、构建系统和任务调度都可建模为偏序。若 Kahn 算法任一时刻可选的入度 0 节点超过一个，通常意味着拓扑序不唯一。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9310, "Dijkstra 最短路", "Dijkstra：非负权最短路与松弛", "cp-algorithms", CP_REPO, "src/graph/dijkstra.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
Dijkstra 维护从源点到各节点的当前最短估计，每次确定距离最小的未处理节点，再用它松弛出边。贪心正确性的关键是所有边权非负：最小候选一旦取出，之后不可能通过更长前缀得到更短路径。稀疏图配合优先队列复杂度 O((V+E)log V)。

## 易错边界
优先队列中可能有过期条目，弹出后需比较距离并跳过；存在负权边时必须改用 Bellman-Ford 等算法。parent 数组用于恢复最短路径。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9311, "Bellman-Ford", "Bellman-Ford：负权边与负环", "cp-algorithms", CP_REPO, "src/graph/bellman_ford.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
Bellman-Ford 重复扫描所有边并执行松弛。若不存在从源点可达的负环，任意最短简单路径至多包含 V-1 条边，因此 V-1 轮后距离稳定。第 V 轮仍能松弛说明存在可达负环；沿 parent 回退 V 次可进入环内并恢复负环。

## 复杂度与优化
时间 O(VE)、空间 O(V)，适合有负权或需要负环检测的图。若某轮没有更新可提前结束。松弛前必须确认起点距离不是无穷，避免溢出或伪更新。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9312, "Floyd-Warshall", "Floyd-Warshall：全源最短路与动态规划", "cp-algorithms", CP_REPO, "src/graph/all-pair-shortest-path-floyd-warshall.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
Floyd-Warshall 的状态 d[i][j] 表示当前允许一组中间节点时 i 到 j 的最短距离。加入中间点 k 后执行 d[i][j]=min(d[i][j],d[i][k]+d[k][j])。三重循环复杂度 O(V^3)，空间 O(V^2)，适合点数较小且需要任意两点查询的场景。

## 负环判断
算法结束后若 d[v][v]<0，则 v 位于或可达某个负环。计算前应把对角线置 0，并在相加前检查两个子距离都不是无穷。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9313, "Kruskal 最小生成树", "Kruskal：按边贪心与并查集", "cp-algorithms", CP_REPO, "src/graph/mst_kruskal_with_dsu.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
Kruskal 将无向图所有边按权值升序扫描，只选择连接两个不同连通分量的边。割性质保证跨越任意割的最轻安全边可以加入某棵最小生成树。并查集负责快速判断是否成环，整体复杂度主要来自排序，为 O(E log E)。

## 结果校验
连通图最终应选 V-1 条边；不足说明原图不连通，此时得到的是最小生成森林。相同权值边可能产生多棵不同但总权相同的最小生成树。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9314, "Prim 最小生成树", "Prim：按点扩展最小生成树", "cp-algorithms", CP_REPO, "src/graph/mst_prim.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
Prim 从任意起点维护“树内集合”，每次选择连接树内与树外的最轻边，把新顶点加入。它与 Dijkstra 都使用优先队列，但 key 的含义不同：Prim 保存接入生成树的最小边权，Dijkstra 保存从源点的路径长度。邻接表加堆适合稀疏图，复杂度 O(E log V)。

## 图条件
最小生成树只针对连通无向带权图。若优先队列耗尽仍有未访问节点，图不连通，应返回生成森林或报告失败。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9315, "强连通分量", "强连通分量与缩点 DAG", "cp-algorithms", CP_REPO, "src/graph/strongly-connected-components.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
有向图中互相可达的最大节点集合称为强连通分量。Kosaraju 先在原图按退出时间排序，再按逆序在反图 DFS；Tarjan 则用 dfn、low 和栈在一次 DFS 中识别分量。把每个分量缩成一个点后必然得到 DAG，可继续做拓扑排序和动态规划。

## 复杂度
两类算法均为 O(V+E)。Tarjan 的栈标记表示节点仍属于尚未封闭的搜索分量，更新 low 时要区分树边与指向栈内节点的返祖边。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9316, "动态规划", "动态规划：状态、转移与计算顺序", "cp-algorithms", CP_REPO, "src/dynamic_programming/intro-to-dp.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
动态规划把具有重叠子问题和最优子结构的问题拆成状态。设计时依次回答：状态表示什么、答案对应哪个状态、转移从哪些已知状态而来、边界如何初始化、按什么顺序保证依赖已计算。自顶向下记忆化易于从递归推导，自底向上迭代通常常数更小并便于空间优化。

## 复杂度估算
总时间通常等于“状态数量 × 每个状态的转移数量”。滚动数组只能在旧层不再被后续状态使用时采用，更新方向也必须避免覆盖仍需读取的值。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9317, "背包动态规划", "0/1、完全与多重背包", "cp-algorithms", CP_REPO, "src/dynamic_programming/knapsack.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
0/1 背包中每件物品最多选一次，状态 f[j] 表示容量 j 的最大价值，压缩为一维后容量必须从大到小更新，防止同一物品被重复使用。完全背包允许无限次选择，容量应从小到大更新，使当前物品的新状态可继续参与转移。多重背包可按数量展开或用二进制分组降复杂度。

## 复杂度
基础 0/1 与完全背包为 O(nW)、空间 O(W)。先明确“恰好装满”还是“不超过容量”，两者的负无穷初始化不同。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9318, "最长递增子序列", "最长递增子序列：DP 与贪心二分", "cp-algorithms", CP_REPO, "src/sequences/longest_increasing_subsequence.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
经典 DP 令 dp[i] 为以 a[i] 结尾的最长递增子序列，枚举前驱得到 O(n^2)。更快方法维护 tails[len]：长度为 len+1 的递增子序列所能达到的最小末尾值。对每个元素二分找到第一个不小于它的位置并替换，复杂度 O(n log n)。

## 边界与恢复
严格递增使用 lower_bound，非递减使用 upper_bound。tails 本身不一定是一条真实子序列；要恢复方案需额外记录每个元素的前驱和所在长度位置。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9319, "KMP 字符串匹配", "前缀函数与 KMP 自动回退", "cp-algorithms", CP_REPO, "src/string/prefix-function.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
前缀函数 π[i] 表示字符串前缀中，与以 i 结尾后缀相等的最长真前缀长度。发生失配时，不必把文本指针回退，而是令当前匹配长度跳到 π[j-1]，复用已知边界。构造前缀函数和 KMP 匹配都为 O(n)，适合单模式串搜索。

## 延伸应用
前缀函数还可求字符串周期、边界出现次数和前缀自动机。拼接 pattern + 分隔符 + text 时，分隔符必须是两边都不会出现的字符。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9320, "字符串哈希", "字符串哈希：滚动哈希与碰撞控制", "cp-algorithms", CP_REPO, "src/string/string-hashing.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
多项式滚动哈希把字符串映射为模整数，预处理前缀哈希与幂后，可 O(1) 比较两个子串哈希。哈希相等只表示“极可能相等”，不是数学上的必然；对抗性输入或高可靠场景应使用双模、随机基数，最终必要时再比较原串。

## 复杂度与规范化
预处理 O(n)，子串查询 O(1)。比较不同起点的子串时，要么使用逆元归一化，要么把两者乘到相同幂次，确保位置权重一致。
"""),
    OpenSourceTopic(2, "computer_organization", 9401, "指令集体系结构", "RISC-V ISA 的模块化设计", "RISC-V ISA Manual", RISCV_REPO, "src/unpriv/intro.adoc", "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/", """
## 中文教学导读
指令集体系结构 ISA 规定软件可见的指令、寄存器、存储访问和异常语义，是编译器、操作系统与处理器实现之间的契约。RISC-V 以基础整数指令集为核心，通过 M、A、F、D、C、V 等标准扩展组合能力。ISA 描述“行为必须是什么”，微体系结构决定流水线、缓存和执行单元“如何实现”。

## 学习要点
区分架构状态与实现细节；同一 ISA 可以有顺序、流水、乱序等不同实现，只要软件可见结果符合规范。
"""),
    OpenSourceTopic(2, "computer_organization", 9402, "基础整数指令", "RISC-V 基础整数指令与编码", "RISC-V ISA Manual", RISCV_REPO, "src/unpriv/base.adoc", "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/", """
## 中文教学导读
基础整数指令覆盖算术逻辑、分支跳转、加载存储和系统控制。典型实现有 32 个通用寄存器，x0 恒为零。R、I、S、B、U、J 等编码格式让 opcode、寄存器编号和立即数在固定字段中复用，降低译码复杂度。加载与存储是访问内存的主要方式，其他算术通常在寄存器之间完成。

## 数据通路联系
一条指令通常经历取指、译码/读寄存器、执行/地址计算、访存和写回。不同指令只启用其中部分路径，控制器负责产生选择和写使能信号。
"""),
    OpenSourceTopic(2, "computer_organization", 9403, "乘除法扩展", "RISC-V M 扩展：乘法与除法", "RISC-V ISA Manual", RISCV_REPO, "src/unpriv/m-st-ext.adoc", "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/", """
## 中文教学导读
M 扩展把整数乘法、除法和取余从软件序列提升为标准指令。乘法可分别取得乘积低位和高位，高位版本区分有符号与无符号操作数。硬件可选择单周期组合乘法器、分阶段流水乘法器或多周期迭代单元，在面积、时延和吞吐率之间权衡。

## 边界语义
除零、最小负数除以 -1 等情况必须按 ISA 规定产生确定结果。微体系结构可以多周期执行，但提交的架构结果必须一致。
"""),
    OpenSourceTopic(2, "computer_organization", 9404, "原子指令", "RISC-V A 扩展与同步原语", "RISC-V ISA Manual", RISCV_REPO, "src/unpriv/a-st-ext.adoc", "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/", """
## 中文教学导读
原子指令用于多核共享内存同步。LR/SC 先保留一个地址，再条件写入；若期间保留失效，SC 失败，软件重试。AMO 指令以不可分割方式完成读—改—写。acquire 与 release 语义约束操作前后的可见顺序，是实现锁、信号量和无锁结构的基础。

## 正确性视角
原子性解决“操作不可被观察为中间状态”，内存序解决“不同核以什么顺序观察普通读写”；两者不能混为一谈。
"""),
    OpenSourceTopic(2, "computer_organization", 9405, "压缩指令", "RISC-V C 扩展与代码密度", "RISC-V ISA Manual", RISCV_REPO, "src/unpriv/c-st-ext.adoc", "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/", """
## 中文教学导读
C 扩展提供常用操作的 16 位编码，与标准 32 位指令混合排列。更高代码密度可以减少指令存储占用和取指带宽，也可能提升 I-Cache 有效容量。压缩指令在译码后通常映射为等价基础操作，因此不必增加新的架构语义。

## 实现影响
取指单元需要处理半字对齐、跨取指边界拼接和分支目标对齐；编译器根据寄存器与立即数限制选择可压缩形式。
"""),
    OpenSourceTopic(2, "computer_organization", 9406, "控制状态寄存器", "CSR：控制、状态与原子更新", "RISC-V ISA Manual", RISCV_REPO, "src/unpriv/zicsr.adoc", "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/", """
## 中文教学导读
控制状态寄存器 CSR 保存处理器配置、异常入口、性能计数和特权状态。CSR 指令可读后写、按位设置或按位清除，使软件在单条架构指令中完成原子更新。CSR 地址空间与权限检查共同限制不同特权级可访问的状态。

## 数据相关
流水线中相邻 CSR 指令可能形成读后写依赖；实现必须转发、暂停或串行化，确保后续指令看到正确的新值。
"""),
    OpenSourceTopic(2, "computer_organization", 9407, "内存一致性模型", "RVWMO：内存顺序与栅栏", "RISC-V ISA Manual", RISCV_REPO, "src/unpriv/rvwmo.adoc", "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/", """
## 中文教学导读
弱内存模型允许处理器和缓存系统在不破坏依赖与显式约束的前提下重排部分读写，以获得更高性能。单线程程序顺序并不等于其他核观察到的全局顺序。FENCE、原子指令的 acquire/release 标记和语言运行时同步原语用于建立 happens-before 关系。

## 分析方法
研究并发结果时应画出每个 hart 的程序顺序、读到关系和同步边，再判断是否形成规范禁止的循环；不能仅凭墙上时钟直觉推断顺序。
"""),
    OpenSourceTopic(2, "computer_organization", 9408, "虚拟存储与页表", "RISC-V 页表、地址翻译与 TLB", "RISC-V ISA Manual", RISCV_REPO, "src/priv/sv.adoc", "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/", """
## 中文教学导读
分页虚拟存储把虚拟页号通过多级页表转换为物理页号，页内偏移保持不变。页表项包含有效、读写执行权限、访问和脏位等属性。TLB 缓存近期翻译，命中时避免页表遍历；缺失时硬件或软件执行 page-table walk。缺页与权限错误通过异常交给操作系统处理。

## 一致性
操作系统修改页表后，需要使用适当的地址翻译栅栏使旧 TLB 项失效。页大小、级数和虚拟地址宽度共同决定页表结构与覆盖范围。
"""),
    OpenSourceTopic(2, "computer_organization", 9409, "CPU 流水线", "Ibex 流水线、停顿与冲刷", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/pipeline_details.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
流水线把不同指令的取指、译码、执行和写回阶段重叠，提高吞吐率而非单条指令的逻辑工作量。数据冒险可通过转发或暂停解决；分支、跳转和异常改变控制流，需要冲刷错误路径指令。多周期乘除法和访存等待会向前级传播停顿。

## 性能公式
理想 CPI 接近 1，实际 CPI = 1 + 数据相关停顿 + 控制转移损失 + 存储等待等额外周期。评价优化时应同时观察频率、CPI、面积和功耗。
"""),
    OpenSourceTopic(2, "computer_organization", 9410, "取指单元", "Ibex 取指、预取缓冲与分支目标", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/instruction_fetch.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
取指单元根据 PC 请求指令存储器，并用缓冲隐藏接口延迟。顺序执行时可预取后续字；分支预测错误、跳转或异常发生后必须丢弃旧路径响应并重定向 PC。存在压缩指令时，一条指令可能从字边界中间开始或跨越边界，需要拼接与对齐逻辑。

## 关键状态
请求地址、返回数据、有效/就绪握手和 outstanding 请求必须保持一致；重定向后迟到的旧响应不能进入译码级。
"""),
    OpenSourceTopic(2, "computer_organization", 9411, "译码与执行", "Ibex 指令译码与执行单元", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/instruction_decode_execute.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
译码阶段从指令位域识别操作类型、源/目的寄存器、立即数格式和控制信号。执行阶段由 ALU、分支比较、乘除法和地址生成等单元完成运算。非法编码、权限不满足或扩展未实现时，应产生精确异常而不是继续执行未定义操作。

## 控制设计
硬布线控制适合规则 RISC 指令，控制信号必须与数据路径同步推进；暂停时保持阶段寄存器，冲刷时把无效标记注入流水线。
"""),
    OpenSourceTopic(2, "computer_organization", 9412, "访存单元", "Ibex Load/Store Unit 与未对齐访问", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/load_store_unit.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
访存单元用基址寄存器加立即数生成有效地址，并根据字节、半字或字访问产生字节使能。加载结果按指令要求做符号扩展或零扩展。未对齐访问可能拆成两次总线事务，若架构或存储区域不允许则触发异常。

## 接口与异常
请求和响应通常通过 valid/ready 握手；访问错误必须与发起指令精确对应。具有副作用的 MMIO 区域尤其不能被随意重复、合并或推测访问。
"""),
    OpenSourceTopic(2, "computer_organization", 9413, "寄存器堆", "Ibex 寄存器堆：端口、时序与 x0", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/register_file.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
寄存器堆为指令提供低延迟操作数。典型整数流水线需要两个读端口和一个写端口；x0 的读取恒为 0，写入被忽略。寄存器堆可用触发器、锁存器或存储宏实现，不同选择影响面积、时序和工艺适配。

## 冒险处理
当本周期写回寄存器恰好被后续指令读取时，设计需规定写优先、读优先或通过旁路网络转发，保证架构结果不依赖器件偶然时序。
"""),
    OpenSourceTopic(2, "computer_organization", 9414, "指令缓存", "Ibex I-Cache：行、Tag 与替换", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/icache.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
指令缓存利用程序的时间与空间局部性保存近期指令块。地址被拆为块内偏移、组索引和 Tag；命中要求有效位与 Tag 匹配。缺失时从下级存储取回整行并选择一路替换。组相联减少冲突缺失，但增加比较器、选择逻辑和功耗。

## 正确性事件
自修改代码、调试写入或权限环境变化后，需要执行规定的指令缓存同步。奇偶校验或 ECC 可检测缓存阵列中的位错误。
"""),
    OpenSourceTopic(2, "computer_organization", 9415, "异常与中断", "Ibex 异常、中断与精确状态", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/exception_interrupts.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
异常由当前指令同步触发，如非法指令、访问故障；中断来自外部或计时器，通常异步到达。处理器接收陷阱时保存返回 PC 和原因，切换到处理入口，并确保更年轻指令没有产生架构可见副作用。返回指令恢复先前特权与中断状态。

## 优先级
同周期多个事件需要确定优先级。精确异常要求已完成的更老指令保留结果、出错及更年轻指令不提交，这与流水线冲刷和提交边界密切相关。
"""),
    OpenSourceTopic(2, "computer_organization", 9416, "性能计数器", "硬件性能计数器与 CPI 归因", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/performance_counters.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
性能计数器记录周期数、退休指令数、分支、缓存缺失和各类停顿事件。基础指标 CPI=cycle/instret；只有把额外周期按数据冒险、分支错误、访存等待等原因拆分，才能定位瓶颈。采样前后读取计数器并求差值，避免把初始化和其他任务混入区间。

## 实验规范
比较两种实现时应固定工作负载、编译选项和输入，报告预热策略与重复次数。单个计数器可能溢出，读取多字宽计数时还要保证一致性。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9321, "0-1 BFS", "0-1 BFS：双端队列维护最短路", "cp-algorithms", CP_REPO, "src/graph/01_bfs.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
当所有边权只有 0 和 1 时，不必使用普通 Dijkstra 的优先队列。0-1 BFS 用双端队列维护候选节点：经 0 权边松弛的节点放到队首，经 1 权边松弛的节点放到队尾，使队列中的距离保持非递减。每条边至多触发常数次处理，总复杂度 O(V+E)。

## 逐步实现
初始化 dist[source]=0，其余为无穷。取出队首 u 后检查所有边 (u,v,w)，若 dist[u]+w 更小则更新；w=0 时 push_front(v)，w=1 时 push_back(v)。它适合最少转换次数、免费/付费通道和方向改变代价等建模。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9322, "图中环检测", "有向图与无向图的环检测", "cp-algorithms", CP_REPO, "src/graph/finding-cycle.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
无向图 DFS 遇到一个已访问且不是父节点的邻居即可发现环；有向图则必须区分“正在递归栈中”和“已经完成”，只有指向灰色节点的回边能证明存在有向环。记录 parent 后可从冲突端点回溯并恢复具体环路。

## 正确性边界
平行边、自环和非连通图都需要单独考虑。并查集可以在线判断无向图逐边加入时是否成环，但不能直接恢复有向环，也不能替代对所有连通分量的遍历。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9323, "桥与割点", "Tarjan low-link：桥、割点与双连通性", "cp-algorithms", CP_REPO, "src/graph/bridge-searching.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
DFS 时间戳 tin[v] 记录首次访问时刻，low[v] 记录从 v 的子树沿树边和至多一条返祖边能到达的最早祖先。树边 (v,to) 是桥当且仅当 low[to] > tin[v]。非根节点 v 是割点，当存在子节点 to 满足 low[to] >= tin[v]；DFS 根需至少有两个独立子树才是割点。

## 易错边界
无向边应使用边编号跳过父边，不能只比较父节点，否则平行边会被误判。算法要从每个未访问节点启动，复杂度 O(V+E)。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9324, "最近公共祖先", "LCA：倍增、欧拉序与树上查询", "cp-algorithms", CP_REPO, "src/graph/lca_binary_lifting.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
倍增法预处理 up[v][j]，表示 v 的第 2^j 个祖先。查询时先把更深节点提升到同一深度，再从最大 j 向下同时提升两个节点，最后返回它们的父节点。预处理 O(n log n)，单次查询 O(log n)，适合静态树上的大量祖先和距离查询。

## 关联应用
树上距离可由 depth[u]+depth[v]-2*depth[lca] 得到。若使用 DFS 进入/退出时间，还可 O(1) 判断祖先关系；根发生变化时可利用三个 LCA 候选推导，而不一定重新预处理。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9325, "欧拉路径", "欧拉路径与 Hierholzer 算法", "cp-algorithms", CP_REPO, "src/graph/euler_path.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
欧拉路径要求每条边恰好经过一次。无向连通图存在欧拉回路当所有非孤立点度数为偶数；存在非闭合欧拉路径当恰有两个奇度点。有向图需检查入度与出度差以及相关节点的连通条件。

## 算法过程
Hierholzer 从合法起点不断沿未使用边前进，走不动时把节点加入答案并回退，最终逆序得到路径。使用邻接表和边使用标记可做到 O(E)，必须按“边”去重而不是按相邻节点去重。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9326, "二分图匹配", "Kuhn 算法与增广路匹配", "cp-algorithms", CP_REPO, "src/graph/kuhn_maximum_bipartite_matching.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
二分图匹配选择互不共享端点的边。Kuhn 算法逐个尝试左侧顶点，通过 DFS 寻找一条交替经过未匹配边和已匹配边的增广路；翻转路径上的匹配状态会使匹配数增加 1。当不存在增广路时，根据 Berge 定理当前匹配最大。

## 复杂度与选择
朴素复杂度 O(VE)，适合中小规模稀疏图；更大实例可使用 Hopcroft-Karp 分层批量寻找最短增广路。每轮 DFS 的 visited 必须按本轮重置或使用时间戳。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9327, "最大流", "Dinic：分层图与阻塞流", "cp-algorithms", CP_REPO, "src/graph/dinic.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
最大流在容量约束和流量守恒下最大化源点到汇点的流量。Dinic 先用 BFS 构造只沿层次递增边前进的分层图，再用 DFS 发送阻塞流；当前弧优化避免重复扫描已经耗尽的边。一般复杂度 O(V²E)，在单位容量网络上更快。

## 建模检查
每条正向边必须配一条初始容量为 0 的反向边，增广时同步修改残量。点容量可拆点，二分图匹配可由源—左部—右部—汇的单位容量网络表示。最终残量图还可恢复最小割。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9328, "最小费用最大流", "最小费用流：残量网络、势能与最短增广路", "cp-algorithms", CP_REPO, "src/graph/min_cost_flow.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
最小费用流在发送指定流量或最大流量的同时最小化总费用。每次在残量网络上寻找从源到汇的最短费用路径并增广，反向边费用取相反数以允许撤销旧决策。存在负费用边时可用 Bellman-Ford，或先求势能后用约化费用配合 Dijkstra。

## 工程边界
总费用是“单位费用 × 实际流量”的累加，容量与费用应使用足够宽的整数类型。要明确目标是固定流量最小费用、最大流最小费用，还是只允许收益为正的增广。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9329, "2-SAT", "2-SAT：蕴含图与强连通分量", "cp-algorithms", CP_REPO, "src/graph/2SAT.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
二元子句 (a∨b) 等价于两条蕴含 ¬a→b 和 ¬b→a。为每个变量建立真假两个节点并求强连通分量；若某变量 x 与 ¬x 位于同一分量，则条件互相推出、问题无解。否则按缩点 DAG 的逆拓扑关系确定一组可行赋值。

## 建模技巧
“至少一个”“不能同时”“二选一”和相等/不等关系都可拆成二元子句。节点编号必须保证 literal 与其否定能稳定互换，赋值方向要与所用 SCC 编号顺序一致。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9330, "Aho-Corasick 自动机", "多模式匹配：Trie、失败指针与 AC 自动机", "cp-algorithms", CP_REPO, "src/string/aho_corasick.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
AC 自动机先把所有模式串插入 Trie，再用 BFS 构造失败指针。匹配文本时若当前字符没有对应转移，就沿失败指针跳到最长可匹配后缀；到达节点时，沿输出链接可得到在当前位置结束的全部模式。构建与扫描总时间近似 O(模式总长+文本长+匹配数)。

## 实现要点
根的失败指针指向自身，BFS 时可补全自动转移以避免查询阶段反复跳转。若只统计出现次数，可先在状态上计数，再按失败树深度逆序累加。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9331, "后缀数组", "后缀数组、排名倍增与 LCP", "cp-algorithms", CP_REPO, "src/string/suffix-array.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
后缀数组 sa 按字典序排列字符串的所有后缀，rank 表示后缀的排序位置。倍增算法在第 k 轮用长度 2^(k-1) 的两个排名对长度 2^k 的前缀排序，配合计数排序可达 O(n log n)。Kasai 算法利用相邻后缀 LCP 至少下降 1 的性质在线性时间求 height 数组。

## 查询能力
模式串可在后缀数组上二分；任意两个后缀的最长公共前缀可转化为 height 区间 RMQ。构建时加入全局最小哨兵字符可统一循环位移和边界处理。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9332, "后缀自动机", "后缀自动机：endpos 等价类与子串统计", "cp-algorithms", CP_REPO, "src/string/suffix-automaton.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
后缀自动机用至多 2n-1 个状态表示一个字符串的全部子串。状态的 len 是该等价类最长串长度，link 指向最长真后缀所在状态；加入新字符时若连续性被破坏，需要克隆状态以保持转移和 endpos 等价关系。

## 典型统计
不同子串数为各状态 len[v]-len[link[v]] 之和；按 len 拓扑顺序传播末端出现次数可求每个子串的出现频率。克隆状态初始不代表新的原串结尾，计数时不能与普通新状态混淆。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9333, "Z 函数", "Z 函数：前缀匹配与线性维护窗口", "cp-algorithms", CP_REPO, "src/string/z-function.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
Z[i] 表示从 i 开始的后缀与整个字符串前缀的最长公共前缀长度。算法维护当前最右匹配区间 [l,r)，若 i 位于区间内就用已有 Z 值给出下界，再继续逐字符扩展；由于 r 只单调右移，总复杂度 O(n)。

## 典型应用
在 pattern#text 上计算 Z 值可寻找模式出现位置，还可判断周期、边界和字符串压缩。分隔符不能出现在模式或文本中，且要明确 Z[0] 采用 0 还是 n 的实现约定。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9334, "Treap", "Treap：随机优先级维护平衡搜索树", "cp-algorithms", CP_REPO, "src/data_structures/treap.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
Treap 同时按 key 满足二叉搜索树性质、按随机 priority 满足堆性质。随机优先级使树高期望为 O(log n)。split 按 key 把一棵树分成两棵，merge 在所有左树 key 小于右树 key 时合并；插入、删除和区间操作都可由二者组合。

## 扩展与维护
隐式 Treap 用子树大小而非显式 key 表示序列位置，可支持区间翻转和聚合。每次修改子节点后必须 pull 更新 size/aggregate，懒标记要在 split、merge 访问子树前 push。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9335, "分块", "平方根分解：预处理与修改的折中", "cp-algorithms", CP_REPO, "src/data_structures/sqrt_decomposition.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
平方根分解把长度 n 的序列划分为约 √n 个块，每块维护和、最值或排序结果。区间查询直接处理两端零散元素，中间完整块使用预计算信息，典型复杂度 O(√n)；单点修改只更新所在块。

## 选择原则
分块比线段树更易适配无法严格合并的信息，也可通过重建处理批量修改，但常数和边界处理依赖块长。块长应根据查询/修改比例调节，而不是机械固定为 floor(√n)。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9336, "快速幂", "二进制快速幂与可结合运算", "cp-algorithms", CP_REPO, "src/algebra/binary-exp.md", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/", """
## 中文教学导读
二进制快速幂把指数写成二进制：每轮按最低位决定是否把当前底数乘入答案，然后底数平方、指数右移。因为只处理 O(log n) 个二进制位，模幂复杂度 O(log n)。这一思想依赖运算满足结合律，可推广到矩阵、置换和函数复合。

## 边界检查
答案从乘法单位元开始；取模乘法要防止中间溢出；指数为 0 时结果应为单位元。负指数只有在逆元存在且定义清晰时才能转化处理。
"""),
    OpenSourceTopic(2, "computer_organization", 9417, "位操作扩展", "RISC-V B 扩展与位级数据通路", "RISC-V ISA Manual", RISCV_REPO, "src/unpriv/b-st-ext.adoc", "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/", """
## 中文教学导读
位操作扩展提供旋转、位计数、单位置位/清位、字段提取等指令，可缩短编译器原本需要多条移位与逻辑运算组成的序列。它们仍只改变寄存器中的整数位模式，不访问内存，也不改变基础指令的架构状态模型。

## 数据通路分析
实现可复用 ALU 的移位器和逻辑单元，也可增加前导零计数等专用组合电路。评估一条扩展指令时，应同时比较指令数、关键路径、面积和能耗，而不只看单条指令延迟。
"""),
    OpenSourceTopic(2, "computer_organization", 9418, "浮点运算", "RISC-V F 扩展与 IEEE 754 舍入异常", "RISC-V ISA Manual", RISCV_REPO, "src/unpriv/f-st-ext.adoc", "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/", """
## 中文教学导读
IEEE 754 单精度数由符号、阶码和尾数组成，除规格化数外还包含 ±0、非规格化数、±∞ 和 NaN。浮点加法需要对阶、尾数运算、规格化和舍入；乘法则组合符号、指数与有效数乘积。不同舍入模式会影响边界结果。

## 异常语义
无效操作、除零、溢出、下溢和不精确通过状态标志累计，而不一定像整数非法指令那样立即陷入。比较浮点结果时必须考虑 NaN 的无序语义和 +0/-0 的特殊规则。
"""),
    OpenSourceTopic(2, "computer_organization", 9419, "向量处理", "RISC-V V 扩展：向量长度无关执行", "RISC-V ISA Manual", RISCV_REPO, "src/unpriv/v-st-ext.adoc", "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/", """
## 中文教学导读
向量扩展用一条指令处理多个元素。软件通过 SEW、LMUL 和 vl 描述元素宽度、寄存器组大小与本轮有效元素数，使同一程序能在不同物理向量长度的处理器上执行。掩码控制逐元素是否生效，尾部和非活动元素策略影响可见结果。

## 性能推演
向量化收益受数据并行度、内存带宽、访存连续性和启动开销共同限制。处理长数组时通常循环设置 vl，完成当前批次后推进指针；若存在数据相关或不规则访存，理论峰值吞吐不一定可达。
"""),
    OpenSourceTopic(2, "computer_organization", 9420, "指令流同步", "FENCE.I：自修改代码与取指一致性", "RISC-V ISA Manual", RISCV_REPO, "src/unpriv/zifencei.adoc", "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/", """
## 中文教学导读
数据存储把新指令写入内存后，取指单元或指令缓存未必立即看到更新。FENCE.I 保证当前 hart 后续取指能观察到之前对指令内存的写入，是 JIT、动态链接和调试器修改代码后的关键同步点。

## 系统边界
FENCE.I 只描述当前 hart 的指令流同步；多核系统还需操作系统协调其他 hart，并结合平台缓存一致性规则。实现可以冲刷流水线和 I-Cache，也可以使用更精细的追踪机制，只要满足架构可见语义。
"""),
    OpenSourceTopic(2, "computer_organization", 9421, "缓存管理操作", "Cache Management Operations：清理、失效与预取", "RISC-V ISA Manual", RISCV_REPO, "src/unpriv/cmo.adoc", "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/", """
## 中文教学导读
缓存块管理操作用于清理脏数据到下级、使缓存行失效或执行二者组合，常见于 DMA、设备共享缓冲区和非一致性系统。预取提示可提前请求未来数据，但通常不承诺一定产生可见缓存状态变化。

## 正确性检查
必须区分 clean、invalidate 和 flush 的语义以及操作作用域。先失效仍含唯一新数据的脏行会造成数据丢失；与设备交互时还要结合内存屏障，确保数据完成顺序而非只改变缓存状态。
"""),
    OpenSourceTopic(2, "computer_organization", 9422, "机器模式", "RISC-V Machine Mode：陷阱入口与系统控制", "RISC-V ISA Manual", RISCV_REPO, "src/priv/machine.adoc", "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/", """
## 中文教学导读
机器模式是 RISC-V 必须实现的最高特权级，负责早期启动、平台控制和向低特权级提供运行环境。陷阱到来时硬件把原因写入 mcause、相关地址写入 mtval、返回地址写入 mepc，并根据 mtvec 进入处理程序。

## 状态转换
mstatus 保存全局中断使能及陷阱前特权状态。处理程序必须区分中断与同步异常，按原因处理后用 mret 恢复；若 mepc 更新错误或在副作用完成前重新开中断，会破坏精确状态。
"""),
    OpenSourceTopic(2, "computer_organization", 9423, "监督模式", "Supervisor Mode：操作系统、委托与地址空间", "RISC-V ISA Manual", RISCV_REPO, "src/priv/supervisor.adoc", "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/", """
## 中文教学导读
监督模式为操作系统内核提供页表、异常处理和受控资源访问。机器模式可通过 medeleg/mideleg 把部分异常和中断委托给 S 模式；stvec、sepc、scause、stval 与 sstatus 构成相应陷阱状态。

## 操作系统联系
用户态系统调用通过 ecall 进入内核，页故障由内核检查访问原因并决定补页、修改映射或终止进程。切换地址空间后通常需要按规则执行地址翻译同步，不能把写 satp 误认为自动刷新全部 TLB。
"""),
    OpenSourceTopic(2, "computer_organization", 9424, "物理内存保护", "PMP：地址匹配、权限与优先级", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/pmp.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
PMP 用一组配置项描述物理地址范围及读、写、执行权限，在没有完整虚拟内存的微控制器中也能隔离固件、用户任务和设备区域。常见匹配方式包括 TOR、NA4 与 NAPOT，多个条目同时可能匹配时由较低编号优先。

## 配置检查
权限检查必须覆盖一次访问的全部字节，未对齐访问被拆分后每个事务都要合法。锁定位可阻止后续软件修改配置，启用前应验证启动代码与陷阱入口仍可执行，避免把系统永久锁死。
"""),
    OpenSourceTopic(2, "computer_organization", 9425, "硬件调试", "Ibex Debug：停机、单步与调试返回", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/debug.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
调试请求让处理器在精确定义的指令边界进入 Debug Mode，保存停止原因和恢复地址，然后从调试 ROM 或模块执行命令。单步要求每次只退休一条指令后重新进入调试，断点则可由触发器或软件替换指令实现。

## 流水线交互
进入调试时必须阻止更年轻指令提交并处理尚未完成的访存；退出使用 dret 恢复。调试请求、异常和中断同周期出现时需要明确优先级，避免恢复到错误 PC 或重复执行具有副作用的指令。
"""),
    OpenSourceTopic(2, "computer_organization", 9426, "处理器安全强化", "Ibex Security：锁步、完整性与故障响应", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/security.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
处理器安全不仅是访问权限，还包括对故障注入和控制流破坏的检测。冗余计数器、编码状态机、寄存器或缓存完整性校验以及双核锁步可以提高篡改被发现的概率；检测后还必须进入定义明确的告警或复位路径。

## 威胁建模
每项机制应对应具体资产、攻击面和覆盖故障类型。冗余逻辑若共享同一时钟、电源或综合优化路径，仍可能受共因故障影响；安全配置也会增加面积、时序和验证成本。
"""),
    OpenSourceTopic(2, "computer_organization", 9427, "CSR 实现", "Ibex CSR：寄存器映射、权限与副作用", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/cs_registers.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
CSR 模块集中保存特权状态、中断使能、陷阱信息和性能配置。译码器根据 CSR 地址判断最低访问权限和只读属性，CSRRS/CSRRC 在源寄存器为 x0 时不应产生写副作用。未实现或越权访问必须按架构要求触发非法指令异常。

## 时序一致性
同周期普通写、陷阱硬件更新和返回恢复可能竞争同一字段，设计必须给出确定优先级。对计数器等跨周期变化状态，读取、冻结和溢出行为也要与软件接口一致。
"""),
    OpenSourceTopic(2, "computer_organization", 9428, "形式化接口", "RVFI：用退休事件验证架构一致性", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/rvfi.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
RISC-V Formal Interface 在指令退休边界暴露指令字、前后 PC、寄存器读写、内存访问和陷阱等架构事件。形式化工具把这些事件与 ISA 参考规则比较，可在所有受约束输入路径上发现反例，而不仅依赖随机仿真的有限样本。

## 建模边界
多周期指令、被冲刷指令和异常指令必须只在正确时刻产生 RVFI 事件；内存掩码要准确描述实际访问字节。环境假设过强会隐藏真实错误，过弱则会制造协议上不可能的反例。
"""),
    OpenSourceTopic(2, "computer_organization", 9429, "处理器验证", "Ibex Verification：参考模型、覆盖率与回归", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/verification.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
处理器验证通常组合定向测试、约束随机指令流、参考模型逐条比对、断言和形式化证明。功能覆盖回答重要场景是否发生，代码覆盖提示实现哪些分支未被触达，但二者都不能单独证明设计正确。

## 回归闭环
每个失败应保留随机种子、配置、波形和最小复现；修复后加入回归用例。测试计划要从架构特性分解到检查点与覆盖项，并特别覆盖异常、中断、停顿、冲刷及并发握手的交叉场景。
"""),
    OpenSourceTopic(2, "computer_organization", 9430, "数据冒险", "流水线数据冒险：旁路、暂停与可用时刻", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/pipeline_details.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
后续指令读取前序尚未写回的寄存器会产生 RAW 数据冒险。若结果已在执行级或写回级生成，可用旁路直接送到操作数选择器；Load 的数据通常到访存响应后才可用，因此紧随其后的使用者可能仍需暂停。

## 周期推演
分析冒险要标出“生产者何时产生结果”和“消费者何时需要操作数”，再决定旁路源或停顿周期。暂停时 PC 与相关阶段寄存器必须保持，而更老指令仍应允许前进，避免重复提交。
"""),
    OpenSourceTopic(2, "computer_organization", 9431, "控制冒险", "控制冒险：分支预测、重定向与冲刷流水线", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/pipeline_details.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
分支结果和目标地址尚未确定时，取指已经沿某条路径继续。静态预测可按固定规则选择方向，动态预测则利用历史；预测错误后必须重定向 PC，并把错误路径上的年轻指令标记无效，确保它们不写寄存器或存储器。

## 性能计算
分支额外 CPI 近似为分支频率×错误预测率×错误代价。把分支判断前移可降低代价，却可能拉长译码关键路径；更复杂预测器提高准确率，也增加面积、功耗和状态恢复复杂度。
"""),
    OpenSourceTopic(2, "computer_organization", 9432, "总线握手与访存时序", "Valid/Ready 握手、背压与精确访存", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/load_store_unit.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
Valid/ready 接口中，发送方在 valid=1 且 ready=0 时必须保持地址、数据和控制稳定；只有二者同周期为 1 才完成一次传输。背压允许接收方延迟处理，但处理器必须记录请求是否已经接受、响应属于哪条指令。

## 正确性边界
一次请求不能因流水线暂停被重复发出，已接受请求也不能因冲刷直接遗忘。未对齐访问拆成两个事务时，若第二次失败，架构需要按实现契约处理部分副作用，并确保错误与原指令精确关联。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9337, "算法复杂度与增长率", "渐近分析：O、Ω、Θ 与增长率", "OpenDSA", OPENDSA_REPO, "RST/en/SeniorAlgAnal/GrowthRate.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
渐近分析关注输入规模 n 增大时资源消耗的增长趋势。O 给出渐近上界，Ω 给出下界，Θ 给出同阶紧确界；分析时忽略常数因子和低阶项，但不能忽略输入模型、基本操作定义和最坏/平均/最好情况的区别。

## 分析步骤
先确定输入规模，再统计主导操作执行次数，化简为函数并给出界。循环嵌套不一定直接相乘，递归程序需要写出递推式；摊还分析描述一组操作的平均代价，不等同于概率意义的平均情况。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9338, "线性表抽象数据类型", "List ADT：位置、接口与表示独立性", "OpenDSA", OPENDSA_REPO, "RST/en/List/ListADT.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
线性表是有限、有序的元素序列，“有序”表示元素具有位置，不代表按值排序。ADT 规定插入、删除、访问、定位和长度等操作的行为，不暴露内部采用数组还是链式节点，使调用者依赖契约而非实现细节。

## 接口边界
需要明确位置范围、空表行为、重复元素与失败返回。相同 List ADT 可有不同复杂度实现，因此题目中的操作比例和访问模式决定具体数据结构选择。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9339, "顺序表与动态数组", "动态数组：连续存储、扩容与摊还复杂度", "OpenDSA", OPENDSA_REPO, "RST/en/List/ListArray.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
顺序表把元素连续存储，按下标访问 O(1)，中间插入删除需要移动后缀。动态数组容量不足时申请更大空间并搬移元素；若按常数倍扩容，虽然单次扩容 O(n)，连续 append 的均摊代价仍为 O(1)。

## 工程权衡
连续布局具有良好缓存局部性和较低元数据开销，但扩容会暂时需要额外空间并使旧地址失效。缩容策略过于激进可能在容量边界反复搬迁。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9340, "单链表", "单链表：指针重连、头结点与遍历", "OpenDSA", OPENDSA_REPO, "RST/en/List/ListLinked.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
单链表节点保存元素和 next 指针，已知插入位置前驱时可 O(1) 插入或删除，但按下标定位仍需 O(n) 遍历。哨兵头结点能统一空表与表首操作，减少特殊分支。

## 正确性检查
修改指针前要保存仍需访问的后继；删除后更新长度并按语言内存模型释放或断开节点。反转链表时维护 previous、current、next 三个角色，防止丢失未处理部分。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9341, "双向链表", "双向链表：前驱后继与边界维护", "OpenDSA", OPENDSA_REPO, "RST/en/List/ListDouble.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
双向链表节点同时保存 prev 和 next，可从已知节点 O(1) 删除并支持双向遍历，代价是额外指针空间和更多写操作。首尾哨兵可把表头、表尾和空表统一为相同的四指针重连过程。

## 不变式
任意相邻节点必须满足 x.next.prev=x 与 x.prev.next=x。插入删除后应同时验证首尾哨兵、长度和局部双向一致性，遗漏任一方向都会形成难以发现的悬挂链。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9342, "栈的实现", "栈：数组实现、链式实现与调用栈", "OpenDSA", OPENDSA_REPO, "RST/en/List/StackArray.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
栈只允许在同一端 push、pop 和 top，保持后进先出。数组栈使用 top 下标，空间连续且常数小；链式栈在表头增删，无需连续扩容。三项核心操作都应为 O(1)。

## 应用联系
括号匹配、表达式求值、撤销操作和 DFS 都依赖未完成状态的逆序恢复。运行时调用栈还保存返回地址、局部变量和寄存器现场，递归深度过大会导致栈空间耗尽。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9343, "队列与循环队列", "队列：FIFO、循环数组与空满判定", "OpenDSA", OPENDSA_REPO, "RST/en/List/Queue.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
队列在尾部入队、头部出队，保持先进先出。循环数组通过模运算复用前部空位，避免每次出队搬移元素；链式队列维护 head 与 tail，也能保持 O(1) 入队出队。

## 易错边界
循环队列必须区分空与满，可牺牲一个槽位、额外保存 size 或使用单调计数器。最后一个元素出队后要同步恢复首尾状态，容量为 1 和环绕位置是重点测试场景。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9344, "二叉树基础", "二叉树术语、性质与结构分类", "OpenDSA", OPENDSA_REPO, "RST/en/Binary/BinaryTreeIntro.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
二叉树每个节点至多有左右两个孩子。根、叶子、深度、高度、子树是描述结构的基础；满二叉树、完全二叉树和平衡二叉树约束不同，不能混用。高度为 h 的二叉树节点数量存在上下界。

## 表示方式
链式节点适合一般形态；完全二叉树可用数组表示，0-based 下标 i 的孩子通常为 2i+1、2i+2。选择表示前要区分结构是否稠密、是否频繁修改以及是否需要父指针。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9345, "二叉树遍历", "前序、中序、后序与层序遍历", "OpenDSA", OPENDSA_REPO, "RST/en/Binary/BinaryTreeTraversal.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
前序按根—左—右访问，适合复制或序列化；中序在二叉搜索树上产生有序序列；后序先处理孩子再处理根，适合释放与自底向上计算；层序使用队列按深度展开。

## 复杂度与实现
每种完整遍历都访问 n 个节点，时间 O(n)。递归空间由树高决定，退化树最坏 O(n)；迭代写法需要显式栈并正确记录“节点是否已经展开”的状态。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9346, "二叉搜索树操作", "BST：查找、插入、删除与退化", "OpenDSA", OPENDSA_REPO, "RST/en/Binary/BST.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
BST 保持左子树关键字小于根、右子树大于根的有序不变式。查找与插入沿单条根到叶路径进行；删除有两个孩子的节点时，用前驱或后继替换，再删除至多一个孩子的节点。

## 性能边界
操作时间为 O(h)，平衡时 h=O(log n)，按有序数据插入可能退化为 O(n)。重复键策略必须显式规定为计数、固定一侧或拒绝，否则遍历和删除语义会不一致。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9347, "堆与优先队列", "二叉堆：上滤、下滤与建堆", "OpenDSA", OPENDSA_REPO, "RST/en/Binary/Heaps.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
二叉堆是满足堆序的完全二叉树，通常用数组紧凑保存。插入先放末尾再上滤，删除堆顶则把末尾元素移到根后下滤，均为 O(log n)；自底向上从最后一个内部节点下滤可 O(n) 建堆。

## 使用边界
堆只保证父子局部顺序，不保证数组整体有序；查找任意元素仍可能 O(n)。优先队列适合持续取最值，不适合频繁查询中间排名，更新任意元素需要保存位置索引。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9348, "AVL 树", "AVL：平衡因子与四类旋转", "OpenDSA", OPENDSA_REPO, "RST/en/SearchStruct/AVL.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
AVL 树要求每个节点左右子树高度差绝对值不超过 1。插入或删除后沿祖先链更新高度，遇到失衡节点时根据重路径形成 LL、RR、LR 或 RL 情况，使用一次或两次旋转恢复平衡。

## 旋转不变式
旋转必须同时保持 BST 中序顺序、正确连接父子关系并重新计算高度。AVL 高度严格为 O(log n)，查询稳定，但更新可能比平衡条件更宽松的树产生更多旋转维护。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9349, "红黑树", "红黑树：颜色约束与黑高平衡", "OpenDSA", OPENDSA_REPO, "RST/en/SearchStruct/RedBlack.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
红黑树通过根黑、红节点孩子为黑、任意节点到后代空叶路径黑节点数相同等约束控制高度。最长根叶路径不超过最短路径两倍，因此查找、插入和删除保持 O(log n)。

## 修复思路
更新后根据父、叔、祖父颜色选择重新着色或旋转，把局部冲突向上消解。实现重点是哨兵空叶、根颜色和旋转后父指针；它以较宽松平衡换取较少的更新调整。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9350, "哈希表基础", "哈希表：装载因子、冲突与期望复杂度", "OpenDSA", OPENDSA_REPO, "RST/en/Hashing/HashIntro.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
哈希表通过哈希函数把键映射到有限槽位，不同键落到同一位置形成冲突。装载因子 α=元素数/槽位数直接影响冲突概率；在分布合理且扩容及时的假设下，查找、插入、删除期望 O(1)。

## 正确性边界
哈希值相同不表示键相等，命中候选后仍需比较完整键。最坏情况可退化为 O(n)，因此需要合适的冲突处理、容量策略，并在对抗性输入下考虑随机化或安全哈希。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9351, "哈希函数设计", "哈希函数：均匀性、确定性与键混合", "OpenDSA", OPENDSA_REPO, "RST/en/Hashing/HashFunc.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
哈希函数必须对同一键稳定返回相同结果，并尽量把实际键集合均匀分散。整数可通过混合高低位后取模，字符串常用逐字符多项式累积；只取键的某一小段容易保留输入模式并产生聚集。

## 设计检查
容量与取模策略会和哈希函数共同影响分布。应使用真实或模拟键集统计桶分布和最长链，而不能只凭公式判断；语言对象还必须保持 equals 相等则 hash 相等的契约。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9352, "开放定址哈希", "开放定址：探测序列、删除标记与再散列", "OpenDSA", OPENDSA_REPO, "RST/en/Hashing/OpenHash.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
开放定址把所有元素放在表数组内，冲突时按线性探测、二次探测或双重哈希寻找下一槽。查找必须沿相同序列直到找到键或真正空槽；删除不能直接置空，需要墓碑标记以免截断其他键的探测链。

## 性能维护
装载因子接近 1 时探测长度急剧增加，通常在阈值前扩容并重新散列。线性探测缓存友好但有主聚集，双重哈希分散更好但计算与访问局部性成本更高。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9353, "插入排序", "插入排序：有序前缀与自适应性", "OpenDSA", OPENDSA_REPO, "RST/en/Sorting/InsertionSort.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
插入排序维护已经有序的前缀，把下一个元素向左移动到正确位置。最坏和平均时间 O(n²)，但近乎有序时移动次数少、可接近 O(n)；原地实现且在比较规则正确时稳定。

## 使用场景
它适合小数组和混合排序算法的末端阶段。实现时先保存待插入值，再移动大于它的元素；若用交换代替移动会增加写次数，稳定性取决于是否跨过相等元素。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9354, "选择排序", "选择排序：最值选择与固定比较次数", "OpenDSA", OPENDSA_REPO, "RST/en/Sorting/SelectionSort.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
选择排序每轮从未排序区间找最小值，与区间首元素交换，使有序前缀增长。无论输入是否已有序，比较次数都是 Θ(n²)，交换次数只有 O(n)，原地但普通实现不稳定。

## 对比价值
当写操作代价远高于比较时，少交换可能有意义；一般内存排序中它通常不如插入排序的自适应性。不能把“每轮选最小”误写成遇到更小就立即交换，否则会增加写次数。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9355, "归并排序", "归并排序：分治、稳定合并与空间代价", "OpenDSA", OPENDSA_REPO, "RST/en/Sorting/Mergesort.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
归并排序把序列递归分成两半，分别排序后用双指针线性合并。递推式 T(n)=2T(n/2)+Θ(n)，最好、平均和最坏均为 Θ(n log n)，正确处理相等元素时稳定。

## 工程权衡
数组版本通常需要 O(n) 辅助空间；链表可通过改指针合并。它的顺序访问适合外部排序和并行处理，但递归边界、临时数组复用和合并区间端点容易出错。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9356, "快速排序", "快速排序：划分不变式与枢轴选择", "OpenDSA", OPENDSA_REPO, "RST/en/Sorting/Quicksort.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
快速排序选取枢轴并划分，使一侧元素不大于枢轴、另一侧不小于枢轴，再递归处理两侧。平均 Θ(n log n)、最坏 Θ(n²)，通常原地且缓存行为好，但普通实现不稳定。

## 稳健实现
随机枢轴或三数取中降低持续极端划分风险，三路划分能高效处理大量重复值。递归处理较小一侧并循环处理较大一侧可把额外栈空间控制为 O(log n)。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9357, "堆排序", "堆排序：原地建堆与选择最大值", "OpenDSA", OPENDSA_REPO, "RST/en/Sorting/Heapsort.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
堆排序先把数组原地建成最大堆，再反复交换堆顶与未排序区间末尾、缩小堆并下滤。建堆 O(n)，n 次删除最大值使总时间 Θ(n log n)，最坏界稳定且额外空间 O(1)。

## 取舍
堆排序通常不稳定，访问跨度大导致缓存局部性弱于快速排序。下滤时只能在当前堆大小内比较孩子，已放到数组尾部的有序元素不再属于堆。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9358, "基数排序", "基数排序：按位稳定分配与非比较排序", "OpenDSA", OPENDSA_REPO, "RST/en/Sorting/RadixSort.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
基数排序按数字位或字符位置多轮分配。LSD 从低位到高位，每一轮必须使用稳定排序，才能保留低位已经建立的次序；时间约 O(d(n+k))，其中 d 为位数、k 为每位基数。

## 适用前提
它不是基于比较的通用排序，因此不受 Ω(n log n) 比较下界直接约束，但依赖键能分解为有限位。负数、变长字符串、基数大小和额外桶空间都需要明确编码策略。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9359, "比较排序下界", "决策树模型与 Ω(n log n) 下界", "OpenDSA", OPENDSA_REPO, "RST/en/Sorting/SortingLowerBound.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
把确定性比较排序看作二叉决策树，每次比较产生一个分支，n 个互异元素的 n! 种排列至少对应 n! 个叶子。因此树高至少 log₂(n!)=Ω(n log n)，任意基于比较的排序最坏都不能突破该界。

## 边界说明
下界只约束比较模型。计数、桶和基数排序利用键范围或表示结构获取额外信息，可以在线性附近运行，但其时间和空间会依赖值域、位数或分布假设。
"""),
    OpenSourceTopic(1, "data_structures_algorithms", 9360, "图的存储表示", "邻接矩阵、邻接表与边集", "OpenDSA", OPENDSA_REPO, "RST/en/Graph/GraphImpl.rst", "MIT", "https://opensource.org/license/mit", """
## 中文教学导读
邻接矩阵占 O(V²) 空间，可 O(1) 判断任意两点是否有边，适合稠密图；邻接表占 O(V+E)，遍历某点邻边与度数成正比，适合稀疏图；边集便于 Kruskal、Bellman-Ford 等按边扫描算法。

## 实现约定
无向边在邻接表中通常保存两个方向，遍历复杂度中的边访问数会成为 2E。要明确平行边、自环、权值、节点编号与删除需求，不能只根据 V 选择表示。
"""),
    OpenSourceTopic(2, "computer_organization", 9433, "数制与进制转换", "二进制、十六进制与位宽解释", "RISC-V ISA Manual", RISCV_REPO, "src/unpriv/base.adoc", "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/", """
## 中文教学导读
二进制位是硬件存储与运算的基本单位，十六进制每一位对应 4 个二进制位，便于阅读地址和指令编码。相同位串必须结合位宽和解释方式才有数值含义；扩展位宽时无符号数补 0，有符号补码通常复制符号位。

## 计算规范
进制转换应先固定宽度并从最低位分组，结果不足位时显式补齐。地址、机器码和掩码书写要保留下划分字段，避免把数值相等误认为编码语义相同。
"""),
    OpenSourceTopic(2, "computer_organization", 9434, "补码与有符号数", "补码范围、取负与符号扩展", "RISC-V ISA Manual", RISCV_REPO, "src/unpriv/base.adoc", "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/", """
## 中文教学导读
n 位补码表示范围为 -2^(n-1) 到 2^(n-1)-1，最高位权值为负。取负可按位取反加 1，但最小负数没有对应的正数；加减法使用同一加法器，硬件按位计算，软件解释结果是否有符号。

## 扩展与比较
符号扩展复制最高位以保持数值，无符号扩展补 0。有符号和无符号比较对最高位解释不同，同一位串可能得到相反大小关系。
"""),
    OpenSourceTopic(2, "computer_organization", 9435, "整数溢出", "进位、溢出与饱和运算的区别", "RISC-V ISA Manual", RISCV_REPO, "src/unpriv/base.adoc", "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/", """
## 中文教学导读
无符号加法溢出表现为最高位产生进位；补码加法在两个同号操作数得到异号结果时溢出，也可用符号位的入进位与出进位异或检测。RISC-V 基础整数加法按模 2^XLEN 保留低位，不自动陷入。

## 语义区别
环绕、陷入和饱和是三种不同策略。判断溢出必须根据指令和数据类型选择规则，不能仅检查最终最高位；地址计算还需防止软件层长度相加溢出造成越界。
"""),
    OpenSourceTopic(2, "computer_organization", 9436, "字节序与数据对齐", "端序、地址布局与未对齐访问", "RISC-V ISA Manual", RISCV_REPO, "src/unpriv/base.adoc", "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/", """
## 中文教学导读
小端序把多字节数的最低有效字节放在最低地址，大端序相反；端序影响内存字节排列，不改变寄存器内位权。自然对齐要求地址是访问宽度的倍数，可简化单次存储事务与缓存访问。

## 调试方法
画出连续地址和每个字节值，再按加载宽度重组。网络协议、文件格式和 MMIO 可能规定特定端序；未对齐访问是否由硬件拆分、产生异常或由软件模拟取决于架构与平台。
"""),
    OpenSourceTopic(2, "computer_organization", 9437, "立即数与符号扩展", "RISC-V 立即数字段拼接与符号扩展", "RISC-V ISA Manual", RISCV_REPO, "src/unpriv/base.adoc", "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/", """
## 中文教学导读
I、S、B、U、J 格式把立即数位分布在不同指令字段中，但通常把符号位放在指令最高位以并行完成符号扩展。分支和跳转立即数还隐含最低位为 0，用有限编码位扩大可达范围。

## 译码检查
先按格式拼接位段，再补隐含低位，最后从正确位宽符号扩展到 XLEN。字段排列是编码布局，不应直接把指令连续切片当作最终立即数。
"""),
    OpenSourceTopic(2, "computer_organization", 9438, "布尔运算与 ALU", "布尔代数、位运算与算术逻辑单元", "RISC-V ISA Manual", RISCV_REPO, "src/unpriv/b-st-ext.adoc", "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/", """
## 中文教学导读
AND、OR、XOR、NOT 对每一位独立运算，可实现掩码、字段清零和状态组合。ALU 在控制信号选择下复用加法、比较、逻辑与移位数据通路，零标志、比较结果或地址结果再送往分支和访存控制。

## 推导联系
减法可用补码加法实现，比较可由减法符号和溢出关系推导，移位器还服务于乘除与位操作扩展。组合路径越复杂，单周期延迟和多路选择成本越高。
"""),
    OpenSourceTopic(2, "computer_organization", 9439, "组合数据通路与多路选择", "数据通路中的 MUX、控制信号与写使能", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/instruction_decode_execute.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
数据通路用多路选择器在寄存器值、立即数、PC 和旁路结果之间选择 ALU 输入，并在 ALU、Load 数据、PC+长度等候选中选择写回值。控制器根据指令类别产生选择码、写使能与异常标记。

## 安全默认值
组合控制必须为所有输出赋默认值，避免综合出锁存器。非法或被冲刷指令的写使能应强制为 0；数据可以无关，但任何架构副作用控制都必须与 valid 同步。
"""),
    OpenSourceTopic(2, "computer_organization", 9440, "多周期控制与有限状态机", "多周期执行单元的状态机设计", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/instruction_decode_execute.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
乘除法、未对齐访存等操作可能跨多个周期，控制器用有限状态机保存进行到哪一步、操作数和剩余工作。开始信号只能接受一次，busy 期间阻止相关指令前进，done 到来后结果必须与原指令重新对应。

## 验证要点
检查复位、取消、异常和连续请求的每个状态转移，保证无死锁、无重复提交。状态编码和默认恢复路径应能处理非法状态，安全设计还可能加入冗余编码检测。
"""),
    OpenSourceTopic(2, "computer_organization", 9441, "指令生命周期", "从取指到退休的指令生命周期", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/pipeline_details.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
一条指令从 PC 发起取指，经对齐、译码、读操作数、执行、访存和写回，最终在退休边界产生架构可见效果。流水线允许多条指令处于不同阶段，但必须保持程序规定的可见顺序与异常语义。

## 状态跟踪
每级寄存器除数据外还需 valid、PC、异常和控制信息。停顿保持状态，前进转移所有关联字段，冲刷清除年轻 valid；只追踪数据而遗漏异常标签会导致错误指令提交。
"""),
    OpenSourceTopic(2, "computer_organization", 9442, "结构冒险", "流水线结构冒险与资源仲裁", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/pipeline_details.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
当同周期多条指令需要同一个不可并发资源时产生结构冒险，例如统一存储端口同时承担取指和数据访问，或单个乘法器尚未完成。解决方式包括暂停、仲裁、复制端口或把资源流水化。

## 取舍
复制资源提高吞吐但增加面积功耗；暂停简单却提高 CPI。仲裁必须固定优先级或保证公平，并把 backpressure 正确传回流水线，防止请求丢失或同一事务重复。
"""),
    OpenSourceTopic(2, "computer_organization", 9443, "存储层次与局部性", "寄存器、Cache、主存与局部性", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/icache.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
存储层次用少量快速存储保存近期热点，缓解处理器与大容量存储的速度差。时间局部性表示近期访问可能再次出现，空间局部性表示邻近地址可能被访问；Cache 行一次搬运多个连续字节利用空间局部性。

## 访问分类
首次访问导致强制缺失，有限容量导致容量缺失，映射竞争导致冲突缺失。扩大行能提高空间利用，也会增加缺失传输延迟和无用数据带宽，需结合工作负载衡量。
"""),
    OpenSourceTopic(2, "computer_organization", 9444, "Cache 性能与 AMAT", "命中率、缺失代价与平均访问时间", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/icache.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
平均存储访问时间 AMAT=命中时间+缺失率×缺失代价。提高相联度可能降低冲突缺失，却增加命中比较和选择延迟；更大容量降低部分缺失，但可能增加面积、功耗与访问周期。

## 测量规范
命中率高不代表性能一定好，必须同时报告访问次数、缺失代价和处理器因缺失实际停顿周期。预热、冷启动和工作集变化会显著改变结果，实验要固定测量区间。
"""),
    OpenSourceTopic(2, "computer_organization", 9445, "存储器映射 I/O", "MMIO、设备寄存器与访问副作用", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/load_store_unit.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
MMIO 把设备控制、状态和数据寄存器映射到物理地址，处理器使用普通 Load/Store 访问。与普通内存不同，读取可能清除状态，写入可能启动设备，因此访问不能随意缓存、合并、重复或推测。

## 驱动检查
寄存器宽度、对齐、端序、保留位和读写属性由设备规范决定。轮询与中断方式各有延迟和 CPU 占用权衡，必要时用内存屏障保证描述符数据在启动命令前可见。
"""),
    OpenSourceTopic(2, "computer_organization", 9446, "中断优先级与响应延迟", "中断采样、屏蔽、优先级与嵌套", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/exception_interrupts.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
中断源先进入 pending 状态，再与 enable、全局开关和特权规则共同决定是否接收。多个中断同时可用时按架构优先级选择；处理器通常在指令边界保存现场并跳转，响应延迟还受长指令、关中断区间和存储等待影响。

## 嵌套设计
允许高优先级中断嵌套可降低关键响应时间，但需要保存更多上下文并防止栈溢出。清除设备中断源与重新开中断的顺序必须避免丢失或重复服务。
"""),
    OpenSourceTopic(2, "computer_organization", 9447, "DMA 与缓存一致性", "DMA 共享缓冲区、Cache 维护与同步", "RISC-V ISA Manual", RISCV_REPO, "src/unpriv/cmo.adoc", "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/", """
## 中文教学导读
DMA 控制器无需 CPU 逐字搬运即可在设备与内存之间传输数据。非硬件一致系统中，CPU Cache 可能持有设备看不到的脏数据，或在设备写入后继续读取旧副本，因此发送前需要清理，接收后需要失效。

## 协议顺序
先准备描述符和缓冲区，再执行必要的 Cache 操作与屏障，最后启动设备；完成后先确认 DMA 已结束，再同步并读取结果。物理地址、所有权转换和缓冲区生命周期必须一致。
"""),
    OpenSourceTopic(2, "computer_organization", 9448, "CPU 性能方程", "执行时间、CPI、主频与加速比", "lowRISC Ibex", IBEX_REPO, "doc/03_reference/performance_counters.rst", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", """
## 中文教学导读
CPU 执行时间=指令数×平均 CPI×时钟周期。提高主频可能拉长流水线并增加分支或访存惩罚，减少指令数也可能使用更慢的复杂指令，因此三个因素必须结合真实工作负载分析。

## 性能比较
加速比=旧时间/新时间；Amdahl 定律说明只优化占比 f 的部分，整体上限受未优化部分限制。报告结果时应区分延迟与吞吐，并固定编译器、输入、热身和测量区间。
"""),
)


# A concrete trace scenario makes each document usable for tutoring, assessment,
# and retrieval instead of behaving like a glossary entry.  These scenarios are
# original EduPath teaching material; upstream text remains separately attributed.
TOPIC_SCENARIOS: dict[int, str] = {
    9301: "在 [1,3,3,5,8] 中寻找第一个大于等于 3 的位置，逐轮记录 l、mid、r 与仍成立的区间不变式。",
    9302: "对序列 [2,1,4,3,5] 求窗口大小为 3 的最大值，写出每一步单调队列保存的下标以及队首过期条件。",
    9303: "依次合并 (1,2)、(3,4)、(2,3)，画出按大小合并前后的父指针，并展示一次 find(4) 的路径压缩。",
    9304: "数组 [2,1,3,4] 建立 1-based 树状数组，执行 add(3,+2) 后计算 prefix(4) 与 range(2,4)。",
    9305: "对 [1,2,3,4] 的区间 [2,4] 加 3，再查询 [1,3] 的和，追踪被整段覆盖节点的 lazy 标记何时下传。",
    9306: "为 [4,6,1,5,7,3] 构造 RMQ 稀疏表，计算查询 [1,5] 所需的 k，并解释两个重叠区间为何不会影响 min。",
    9307: "在无权图 1-{2,3}、2-{4}、3-{4,5} 中从 1 开始 BFS，记录每层队列、首次发现距离和 parent。",
    9308: "对含回边的有向图执行三色 DFS，记录每个节点的 tin、tout 和颜色变化，并指出证明有环的那条边。",
    9309: "给定依赖 A→C、B→C、C→D，运行 Kahn 算法，记录入度变化，并判断拓扑序是否唯一。",
    9310: "从 A 出发处理 A→B=4、A→C=1、C→B=2、B→D=1，记录优先队列中的过期条目和每次松弛。",
    9311: "在含一条负边与一个可达负环的图上执行第 1 到 V 轮松弛，指出第 V 轮更新如何成为负环证据。",
    9312: "用三个节点的距离矩阵演示 k 从 1 到 3 的更新，检查 d[i][k] 与 d[k][j] 为无穷时为何不能直接相加。",
    9313: "按权排序五条边，使用并查集逐条判断是否加入，最终核对所选边数是否为 V-1。",
    9314: "从顶点 A 开始运行 Prim，区分每个节点的 key 与从源点的累计距离，并记录每次扩展生成树的边。",
    9315: "对两个有向环通过单向边连接的图求 SCC，展示 dfn/low 或两遍 DFS 结果，再画出缩点后的 DAG。",
    9316: "以爬楼梯为例，从递归式推导状态、边界和计算顺序，再把二维/完整状态压缩为只保存最近两项。",
    9317: "容量 5、物品 (2,3)、(3,4) 下分别执行 0/1 与完全背包，比较容量循环方向造成的状态差异。",
    9318: "对 [3,1,2,5,4] 逐个更新 tails，记录每次 lower_bound 的位置，并说明 tails 为何不一定是最终子序列。",
    9319: "对模式 ababaca 构造前缀函数，并在一次失配时沿 π 链回退，验证文本指针没有后退。",
    9320: "预处理字符串 abac 的前缀哈希，比较两个等长子串时完成幂次归一化，并讨论一次碰撞如何被双哈希降低。",
    9401: "比较同一 RISC-V 程序在两级顺序核和五级流水核上的执行：列出保持相同的架构状态与不同的微结构状态。",
    9402: "选取一条 add、lw 和 beq，标出 opcode、rd、rs1、rs2、立即数字段，并追踪其经过的数据通路部件。",
    9403: "用 8 位迭代乘法器演示 13×11 的移位加法过程，再比较组合、迭代和流水实现的延迟与吞吐。",
    9404: "两个 hart 同时执行自增共享计数器，比较普通 load/add/store 与 LR/SC 在发生干扰时的状态序列。",
    9405: "把一段含常用寄存器和小立即数的指令序列压缩，计算代码字节数变化并分析跨 32 位边界的取指。",
    9406: "以 CSRRS 为例追踪读出旧值、按位设置和写回；当 rs1=x0 时检查为何只能读而不能产生写副作用。",
    9407: "用 store data、store flag 与另一核 load flag/load data 的消息传递例子，说明 release/acquire 防止了哪种可见重排。",
    9408: "把一个 Sv39 虚拟地址拆成 VPN[2:0] 与页内偏移，逐级查页表，检查权限位并组合最终物理地址。",
    9409: "让 ALU 指令、Load-use 依赖和分支连续进入流水线，逐周期标注前递、停顿、冲刷以及最终退休顺序。",
    9410: "发出两个取指请求后立即发生分支重定向，区分新旧 epoch，并说明迟到的旧路径响应为何必须丢弃。",
    9411: "对一条非法编码和一条合法 ADD 指令分别产生译码控制信号，验证非法指令不会写回且会携带异常进入提交边界。",
    9412: "执行地址跨字边界的半字 Load，拆分两次总线事务、拼接字节并完成符号扩展，同时追踪第二次访问失败的处理。",
    9413: "构造同周期写 x5、下一条读 x5 的场景，分别推演写优先寄存器堆和显式旁路两种实现。",
    9414: "对 2 路组相联 I-Cache 给定地址序列，拆解 offset/index/tag，记录命中、替换和有效位变化。",
    9415: "让非法指令与外部中断在相邻周期到达，标出 mepc、mcause 更新和流水线冲刷，验证精确异常边界。",
    9416: "在固定工作负载前后读取 cycle、instret、load stall 和 branch miss，计算 CPI 并把额外周期归因。",
    9321: "在含 0 权传送门和 1 权普通边的图中，从源点运行双端队列，记录哪些节点从队首、哪些从队尾进入。",
    9322: "分别在无向三角形和有向回边图中恢复一条环，比较 parent 跳过规则与灰色节点判定。",
    9323: "删除候选树边后判断连通分量是否增加，再用 low 值不等式解释相同结论。",
    9324: "在七节点树上查询两个不同深度节点的 LCA，先补齐深度差，再从最高二进制位同步提升。",
    9325: "对有两个奇度点的无向图执行 Hierholzer，记录边栈、回退顺序和最后的逆序答案。",
    9326: "从空匹配开始为三个左侧点寻找增广路，展示一次通过改配旧匹配边使匹配数增加的过程。",
    9327: "在四点容量网络上构造分层图并发送阻塞流，更新正反向残量边，再从残量可达集恢复最小割。",
    9328: "为两名工人与两项任务建立容量和费用，执行两次最短增广，解释第二次为何可能使用反向边调整第一次选择。",
    9329: "把“课程 A、B 至少选一门且不能同时选”转成蕴含边，求 SCC 并检查变量与否定是否冲突。",
    9330: "将 he、she、his、hers 插入 Trie，BFS 建失败指针，然后扫描 ushers 并报告所有模式出现位置。",
    9331: "为 banana$ 构造后缀排序与 LCP，使用 height 区间最小值回答 ana 与 anana 的最长公共前缀。",
    9332: "逐字符插入 ababa，记录 last、len、link 和克隆条件，再用状态贡献公式统计不同子串数。",
    9333: "对 aabcaabxaaaz 维护 [l,r) 区间，展示区间内复用旧 Z 值与继续扩展的两种情况。",
    9334: "按 key 插入 4、2、6 并给定优先级，使用 split/merge 重建树，同时检查 size 聚合是否更新。",
    9335: "把长度 10 的数组按块维护区间和，执行一次单点修改和跨三个块的查询，比较实际访问元素数。",
    9336: "计算 3^13 mod 17，逐轮记录指数二进制最低位、base 和 result，再把相同流程替换为矩阵乘法。",
    9417: "把循环移位与前导零计数分别展开为基础指令序列，比较引入 B 扩展后的指令数和新增组合路径。",
    9418: "手工追踪两个指数不同的单精度数相加：对阶、尾数加法、规格化、舍入，并标出可能置位的异常标志。",
    9419: "数组长度 10、硬件本轮 vl=4 时执行三轮向量加法，记录每轮 vl、指针推进及最后尾部元素处理。",
    9420: "JIT 写入一段新指令后先直接跳转、再加入 FENCE.I，比较取指可能观察到的内容与流水线动作。",
    9421: "CPU 写 DMA 缓冲区后由设备读取，按非一致性系统要求排列 clean、屏障和启动设备的顺序。",
    9422: "模拟一次机器定时器中断：保存 mepc/mcause、关闭与恢复中断、执行处理程序并通过 mret 返回。",
    9423: "用户程序执行 ecall 后进入 S 模式，内核读取 scause、推进 sepc、写返回值并执行 sret。",
    9424: "用 TOR 和 NAPOT 各配置一个代码区与数据区，检查跨边界写访问命中哪条 PMP 项及最终权限。",
    9425: "在一条未完成 Load 和后一条 ALU 指令之间收到调试请求，确定安全停止点、dpc 与恢复后执行顺序。",
    9426: "假设一次故障翻转控制状态机位，追踪编码状态检测、告警输出与复位路径，并分析共同原因风险。",
    9427: "同周期发生 CSR 软件写和陷阱硬件更新，列出字段级优先级并验证只读 CSR 与越权访问行为。",
    9428: "为正常 Store、被冲刷 Store 和访存异常各生成一组 RVFI 信号，检查只有退休事件产生架构副作用。",
    9429: "从一项中断嵌套需求拆出定向测试、随机约束、断言、功能覆盖点和参考模型比对项。",
    9430: "让 add x5 后接 sub x6,x5,x7，再接依赖 Load 的指令，逐周期判断可前递结果与必须暂停结果。",
    9431: "给定分支频率 20%、错误率 10%、代价 3 周期，计算额外 CPI，并比较判断前移一拍的收益。",
    9432: "让 ready 连续两周期为 0，验证 valid 期间请求字段保持稳定，并在握手后只产生一次事务。",
    9337: "分别统计单循环、三角形嵌套循环和二分递归的主导操作次数，写出函数并判定 O、Ω 与 Θ。",
    9338: "为课程名单定义 insert、remove、get、find 接口，分别说明空表、越界和重复元素的契约。",
    9339: "从容量 2 开始连续 append 五个元素，记录每次 size、capacity、搬移次数并计算总搬移成本。",
    9340: "在带哨兵的单链表中插入和删除表首、中间、表尾节点，逐步画出 next 指针重连。",
    9341: "从 A↔B↔C 中删除 B，再在 A 与 C 间插入 D，逐项验证双向链接不变式。",
    9342: "用数组栈处理表达式 (a+b)*c，记录每次 token 后操作符栈和输出序列。",
    9343: "在容量 5 的循环队列中执行四次入队、两次出队、三次入队，记录 front、rear、size 的环绕。",
    9344: "对一棵七节点树标注根、叶、深度和高度，并判断它是满、完全、完美还是平衡二叉树。",
    9345: "对同一棵树分别生成前序、中序、后序和层序结果，逐帧记录递归栈或显式栈状态。",
    9346: "向 BST 插入 5,3,7,6,8 后依次删除叶子、单孩子和双孩子节点，检查中序序列。",
    9347: "把 [4,1,7,3,8,5] 自底向上建成最大堆，再删除两次堆顶并记录每次下滤。",
    9348: "向 AVL 依次插入 30,20,10 和 25,28，分别识别 LL 与 LR/RL 失衡并执行旋转。",
    9349: "向红黑树连续插入造成红红冲突的键，记录父、叔、祖父颜色以及重着色或旋转选择。",
    9350: "把八个键插入十个槽，统计装载因子、冲突数、最长链和平均成功查找比较次数。",
    9351: "比较只取字符串首字符与多项式混合两种哈希函数在一组相似课程编号上的桶分布。",
    9352: "在线性探测表中插入发生聚集的键，删除中间键后分别用空槽和墓碑继续查找后续键。",
    9353: "对 [1,4,3,5,2] 逐轮维护有序前缀，记录比较和移动次数并验证相等元素次序。",
    9354: "对 [4,2,4,1] 运行选择排序，记录每轮最小下标、比较数和交换数，并观察稳定性。",
    9355: "把 [6,3,5,1,4,2] 递归拆分并合并，记录每层子问题、比较次数和辅助数组区间。",
    9356: "对含大量重复值的数组分别做二路与三路划分，比较递归区间大小和枢轴位置。",
    9357: "在最大堆数组上交换堆顶与末尾，缩小 heapSize 后下滤，追踪已排序后缀的增长。",
    9358: "对 170,45,75,90,802,24,2,66 按个位、十位、百位稳定分配，记录每轮桶序。",
    9359: "为 3 个互异元素画完整比较决策树，再推广叶子数与树高关系到 n! 种排列。",
    9360: "把同一个稀疏图分别写成邻接矩阵、邻接表和边集，比较查边、遍历邻居和空间成本。",
    9433: "把 0x9A3C 转成 16 位二进制，再分别按无符号和补码解释，验证位宽改变后的结果。",
    9434: "用 8 位补码计算 37+(-12)、-128 取负和符号扩展到 16 位，标出每一步位模式。",
    9435: "分别构造无符号进位但有符号不溢出、以及有符号溢出但判断规则不同的 8 位加法。",
    9436: "把 0x12345678 写入连续四个地址，画出小端布局，再从非对齐地址读取半字。",
    9437: "从一条 B 型和 J 型机器码中拼出立即数，补隐含低位并符号扩展后计算目标 PC。",
    9438: "用掩码清除、设置和翻转寄存器若干位，再画出 ALU 选择加法、逻辑和移位的路径。",
    9439: "对 ADD、LW、JAL 三条指令分别填写 ALU 输入、写回来源、寄存器写使能和异常默认值。",
    9440: "设计一个四状态迭代除法控制器，跟踪 start、busy、done、异常取消和连续请求。",
    9441: "让三条指令同时位于取指、执行和写回阶段，逐周期移动 valid、PC、异常和控制字段。",
    9442: "统一存储端口同周期遇到取指和 Load 请求，比较暂停取指、双端口存储和缓存分离方案。",
    9443: "给定循环访问地址序列，区分时间与空间局部性，并分类强制、容量和冲突缺失。",
    9444: "命中时间 1 周期、缺失率 4%、缺失代价 40 周期时计算 AMAT，并评估相联度提高的收益。",
    9445: "读取会清零的设备状态寄存器，再比较一次、重复和推测读取造成的不同外设行为。",
    9446: "让高低优先级中断与同步异常接近到达，记录 pending、enable、接收顺序和最大响应延迟。",
    9447: "CPU 准备发送缓冲区、DMA 读取并回写结果，按所有权阶段排列 clean、invalidate 和屏障。",
    9448: "比较两台处理器的指令数、CPI 和主频，计算执行时间与加速比，再用 Amdahl 定律检查上限。",
}


TOPIC_ALIASES: dict[int, tuple[str, ...]] = {
    9305: ("segment tree", "lazy propagation", "区间更新", "区间查询"),
    9310: ("非负权最短路", "优先队列最短路", "松弛"),
    9315: ("SCC", "Tarjan", "Kosaraju", "缩点"),
    9319: ("prefix function", "前缀函数", "失配回退"),
    9327: ("Dinic", "残量网络", "增广路", "最小割"),
    9329: ("2-SAT", "蕴含图", "布尔可满足性"),
    9330: ("AC 自动机", "多模式串匹配", "failure link"),
    9331: ("suffix array", "SA", "LCP", "height"),
    9332: ("suffix automaton", "SAM", "endpos", "不同子串"),
    9408: ("Sv39", "TLB", "MMU", "地址翻译", "page table"),
    9409: ("pipeline", "stall", "flush", "hazard"),
    9414: ("I-Cache", "组相联", "cache line", "tag", "替换策略"),
    9418: ("IEEE 754", "FPU", "NaN", "舍入模式"),
    9419: ("RVV", "SIMD", "vector length", "SEW", "LMUL"),
    9424: ("PMP", "物理地址保护", "NAPOT", "TOR"),
    9428: ("RVFI", "formal verification", "指令退休"),
    9430: ("RAW", "forwarding", "bypass", "load-use", "数据相关"),
    9431: ("branch prediction", "mispredict", "redirect", "控制相关"),
    9432: ("valid ready", "backpressure", "总线协议", "握手"),
    9337: ("Big O", "Big Omega", "Big Theta", "渐近复杂度", "增长率"),
    9339: ("dynamic array", "array list", "扩容", "摊还复杂度"),
    9340: ("singly linked list", "单向链表", "哨兵节点", "指针重连"),
    9343: ("circular queue", "FIFO", "环形缓冲区", "front rear"),
    9347: ("binary heap", "priority queue", "heapify", "上滤", "下滤"),
    9348: ("AVL", "平衡因子", "LL RR LR RL", "旋转"),
    9349: ("red-black tree", "红黑树", "黑高", "重新着色"),
    9350: ("hash table", "load factor", "冲突", "装载因子"),
    9352: ("open addressing", "linear probing", "tombstone", "开放寻址"),
    9355: ("merge sort", "稳定排序", "分治", "合并"),
    9356: ("quick sort", "partition", "pivot", "三路划分"),
    9358: ("radix sort", "LSD", "非比较排序", "桶"),
    9360: ("adjacency list", "adjacency matrix", "edge list", "邻接表", "邻接矩阵"),
    9433: ("binary", "hexadecimal", "进制转换", "位宽"),
    9434: ("two's complement", "补码", "sign extension", "有符号整数"),
    9436: ("endianness", "little endian", "alignment", "小端序", "对齐"),
    9437: ("immediate generator", "sign extend", "B-type", "J-type", "立即数"),
    9443: ("memory hierarchy", "temporal locality", "spatial locality", "存储层次", "局部性"),
    9444: ("AMAT", "hit time", "miss rate", "miss penalty", "平均访存时间"),
    9445: ("MMIO", "memory mapped IO", "设备寄存器", "副作用"),
    9447: ("DMA", "cache coherence", "clean invalidate", "共享缓冲区"),
    9448: ("CPU time", "CPI", "clock rate", "Amdahl", "加速比"),
}


def main() -> int:
    parser = argparse.ArgumentParser(description="Build EduPath's attributed open course corpus")
    parser.add_argument("--cp-root", type=Path, required=True)
    parser.add_argument("--riscv-root", type=Path, required=True)
    parser.add_argument("--ibex-root", type=Path, required=True)
    parser.add_argument("--opendsa-root", type=Path, required=True)
    parser.add_argument(
        "--opendsa-commit",
        default="f4e4afcee2fcc0b47a888ebb5648c8ebb659c53c",
        help="Pinned OpenDSA commit used for raw source files",
    )
    parser.add_argument("--output-dir", type=Path, default=Path("data/seed_corpus"))
    parser.add_argument("--max-excerpt-chars", type=int, default=2200)
    args = parser.parse_args()

    service_root = Path(__file__).resolve().parents[1]
    output_dir = args.output_dir if args.output_dir.is_absolute() else service_root / args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)
    roots = {
        CP_REPO: args.cp_root,
        RISCV_REPO: args.riscv_root,
        IBEX_REPO: args.ibex_root,
        OPENDSA_REPO: args.opendsa_root,
    }
    commits = {
        CP_REPO: _git_commit(args.cp_root),
        RISCV_REPO: _git_commit(args.riscv_root),
        IBEX_REPO: _git_commit(args.ibex_root),
        OPENDSA_REPO: args.opendsa_commit.strip(),
    }
    if not re.fullmatch(r"[0-9a-f]{40}", commits[OPENDSA_REPO]):
        raise ValueError("--opendsa-commit must be a 40-character lowercase Git commit")

    generated_at = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    documents: list[dict[str, object]] = []
    for topic in TOPICS:
        root = roots[topic.repo]
        source_file = root / topic.source_path
        if not source_file.is_file():
            raise FileNotFoundError(f"Missing source file: {source_file}")
        commit = commits[topic.repo]
        source_url = f"{topic.repo}/blob/{commit}/{topic.source_path}"
        excerpt = _prepare_excerpt(source_file.read_text(encoding="utf-8", errors="ignore"), args.max_excerpt_chars)
        guide = topic.chinese_guide.strip()
        teaching_extension = _build_teaching_extension(topic)
        aliases = TOPIC_ALIASES.get(topic.knowledge_point_id, ())
        content = (
            f"{guide}\n\n{teaching_extension}\n\n"
            "## 开源来源与许可\n"
            f"- 上游项目：{topic.project}\n"
            f"- 固定版本：{commit}\n"
            f"- 原文链接：{source_url}\n"
            f"- 开源许可：{topic.license}（{topic.license_url}）\n"
            "- 适配说明：EduPath 增加中文教学导读并截取上游原文；未改变原项目归属，衍生内容沿用对应上游许可。\n\n"
            "## 上游原文节选\n"
            f"{excerpt}"
        )
        documents.append({
            "course_id": topic.course_id,
            "course_code": topic.course_code,
            "knowledge_point_id": topic.knowledge_point_id,
            "knowledge_point": topic.knowledge_point,
            "title": topic.title,
            "content": content,
            "source": source_url,
            "project": topic.project,
            "source_repo": topic.repo,
            "source_path": topic.source_path,
            "source_commit": commit,
            "license": topic.license,
            "license_url": topic.license_url,
            "attribution": f"Adapted from {topic.project} contributors",
            "adaptation_notice": "Chinese teaching guide added; upstream excerpt normalized and truncated.",
            "content_depth": "deep_teaching_unit",
            "pedagogy_version": "edupath-deep-v3",
            "difficulty": _topic_difficulty(topic),
            "curriculum_tier": _curriculum_tier(topic),
            "search_aliases": list(aliases),
            "pedagogy_sections": [
                "中文教学导读",
                "学习目标",
                "先修与关联知识",
                "逐步推演案例",
                "方法选择与工程检查",
                "自测题与答案框架",
                "开源来源与许可",
                "上游原文节选",
            ],
            "generated_at": generated_at,
        })

    corpus_path = output_dir / "seed_documents.jsonl"
    with corpus_path.open("w", encoding="utf-8") as handle:
        for document in documents:
            handle.write(json.dumps(document, ensure_ascii=False) + "\n")

    manifest = {
        "schema_version": "edupath-open-corpus-v3",
        "pedagogy_version": "edupath-deep-v3",
        "generated_at": generated_at,
        "documents": len(documents),
        "courses": dict(Counter(str(item["course_code"]) for item in documents)),
        "licenses": dict(Counter(str(item["license"]) for item in documents)),
        "projects": dict(Counter(str(item["project"]) for item in documents)),
        "source_commits": {repo: commit for repo, commit in commits.items()},
        "max_excerpt_chars": args.max_excerpt_chars,
        "quality": {
            "content_chars_min": min(len(str(item["content"])) for item in documents),
            "content_chars_avg": round(sum(len(str(item["content"])) for item in documents) / len(documents)),
            "content_chars_max": max(len(str(item["content"])) for item in documents),
            "scenario_coverage": sum(
                1 for topic in TOPICS if topic.knowledge_point_id in TOPIC_SCENARIOS
            ),
            "deep_teaching_units": sum(
                1 for item in documents if item["content_depth"] == "deep_teaching_unit"
            ),
            "foundation_units": sum(
                1 for item in documents if item["curriculum_tier"] == "foundation_core"
            ),
        },
        "topics": [asdict(topic) for topic in TOPICS],
    }
    (output_dir / "open_source_manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Wrote {len(documents)} attributed documents to {corpus_path}")
    return 0


def _git_commit(root: Path) -> str:
    result = subprocess.run(
        ["git", "-C", str(root), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def _prepare_excerpt(text: str, max_chars: int) -> str:
    text = re.sub(r"^---\s*\n.*?\n---\s*\n", "", text, count=1, flags=re.DOTALL)
    text = re.sub(
        r"(?ms)^\.\. avmetadata::\s*\n(?:[ \t]+:[^\n]*\n)*",
        "",
        text,
    )
    lines: list[str] = []
    in_code = False
    for raw_line in text.replace("\r\n", "\n").splitlines():
        line = raw_line.rstrip()
        stripped = line.strip()
        if stripped.startswith(
            (
                "image::",
                "include::",
                "video::",
                ".. image::",
                ".. figure::",
                ".. odsafig::",
                ".. inlineav::",
                ".. avembed::",
                ".. avmetadata::",
            )
        ):
            continue
        if stripped.startswith(".. ") and not stripped.startswith(".. topic::"):
            continue
        if stripped.startswith(("[[", ":", ".. _")) and not stripped.startswith("::"):
            continue
        if stripped.startswith(("{%", "{{")) and stripped.endswith(("%}", "}}")):
            continue
        if stripped.startswith("```") or stripped == "----":
            in_code = not in_code
            lines.append("```")
            continue
        if in_code and len(line) > 180:
            line = line[:180]
        line = re.sub(r"<[^>]+>", "", line)
        line = re.sub(r"link:[^\[]+\[([^]]+)\]", r"\1", line)
        line = re.sub(r"xref:[^\[]+\[([^]]*)\]", r"\1", line)
        line = re.sub(r":[A-Za-z0-9_-]+:`([^`<>]+)\s*<[^`>]+>`", r"\1", line)
        line = re.sub(r":[A-Za-z0-9_-]+:`([^`]+)`", r"\1", line)
        if stripped or (lines and lines[-1]):
            lines.append(line)
    normalized = "\n".join(lines).strip()
    if len(normalized) <= max_chars:
        return normalized
    cutoff = normalized.rfind("\n", 0, max_chars)
    if cutoff < max_chars // 2:
        cutoff = max_chars
    return normalized[:cutoff].rstrip() + "\n\n[原文节选到此；完整内容请访问固定版本链接]"


def _build_teaching_extension(topic: OpenSourceTopic) -> str:
    if 9337 <= topic.knowledge_point_id <= 9360:
        peers = [item for item in TOPICS if 9337 <= item.knowledge_point_id <= 9360]
    elif 9433 <= topic.knowledge_point_id <= 9448:
        peers = [item for item in TOPICS if 9433 <= item.knowledge_point_id <= 9448]
    else:
        peers = [item for item in TOPICS if item.course_id == topic.course_id]
    position = peers.index(topic)
    prerequisites = [item.knowledge_point for item in peers[max(0, position - 2):position]]
    related = [item.knowledge_point for item in peers[position + 1:position + 3]]
    aliases = TOPIC_ALIASES.get(topic.knowledge_point_id, ())
    scenario = TOPIC_SCENARIOS.get(
        topic.knowledge_point_id,
        f"选择一个最小可运行实例，逐步记录 {topic.knowledge_point} 的输入、内部状态、输出与边界条件。",
    )
    if topic.course_id == 1:
        objectives = (
            f"解释 {topic.knowledge_point} 的适用前提、核心不变式和终止条件。",
            "能够在小规模输入上逐步追踪关键数据结构或状态转移，而不是只背模板。",
            "能够给出时间、空间复杂度，并在约束变化时与相邻方案进行选型。",
        )
        engineering_checks = (
            "先确认输入规模、数据范围、图的方向/权值或操作是否在线，再选择算法。",
            "实现后用空输入、单元素、重复值、极值和不满足前提的反例检查边界。",
            "复杂度必须包含预处理、单次操作与全部操作，均摊复杂度要明确适用条件。",
        )
        answer_focus = "写出不变式或状态定义，按步骤展示状态变化，最后用前提与复杂度解释结论。"
    else:
        objectives = (
            f"解释 {topic.knowledge_point} 的软件可见语义、关键硬件状态和正确性边界。",
            "能够按周期或事务追踪控制信号、数据通路、停顿/冲刷或异常状态变化。",
            "能够从性能、面积、功耗、安全与验证成本中识别至少两项实现权衡。",
        )
        engineering_checks = (
            "先区分 ISA 规定的架构结果与某个处理器采用的微体系结构实现。",
            "检查 valid/ready、写使能、异常优先级及流水线无效标记，确保副作用只发生一次。",
            "性能结论应给出工作负载、周期或计数器依据，不能只用主频或单条延迟判断。",
        )
        answer_focus = "先说明架构契约，再按周期或事务追踪实现，最后检查提交边界和可见副作用。"

    prereq_text = "、".join(prerequisites) if prerequisites else "本课程基础术语与问题建模"
    related_text = "、".join(related) if related else "综合实验、性能测量与开放题"
    alias_text = "、".join(aliases) if aliases else f"{topic.knowledge_point}、{topic.title}"
    objective_lines = "\n".join(f"- {item}" for item in objectives)
    check_lines = "\n".join(f"- {item}" for item in engineering_checks)
    return (
        "## 学习目标\n"
        f"{objective_lines}\n\n"
        "## 先修与关联知识\n"
        f"- 建议先修：{prereq_text}\n"
        f"- 后续关联：{related_text}\n"
        f"- 检索别名：{alias_text}\n"
        f"- 建议难度：{_topic_difficulty(topic)}；建议先独立推演，再查看上游原文核验。\n\n"
        "## 逐步推演案例\n"
        f"{scenario}\n\n"
        "推演时依次完成：①写清输入与约束；②列出每一步关键状态；③标记不变式或架构承诺；"
        "④核对输出与边界；⑤改变一个条件，观察原方法何处失效。\n\n"
        "## 方法选择与工程检查\n"
        f"{check_lines}\n\n"
        "## 自测题与答案框架\n"
        f"1. 为什么该主题中的核心步骤是正确的？请用不变式、状态转移或架构契约回答。\n"
        f"2. 若移除一个关键前提，应继续使用 {topic.knowledge_point}、修改实现，还是切换方案？说明代价。\n"
        "3. 为上述推演案例设计一个最容易暴露错误的边界输入，并写出期望状态序列。\n\n"
        f"答案框架：{answer_focus}引用结论时应能定位到下方固定版本的开源证据。"
    )


def _topic_difficulty(topic: OpenSourceTopic) -> str:
    advanced_ids = {
        9311, 9312, 9315, 9327, 9328, 9329, 9330, 9331, 9332,
        9349, 9359,
        9407, 9408, 9419, 9421, 9426, 9428, 9429, 9447,
    }
    basic_ids = {
        9301, 9302, 9303, 9307, 9308, 9316, 9336,
        9337, 9338, 9339, 9340, 9341, 9342, 9343, 9344, 9345, 9346, 9347,
        9350, 9351, 9353, 9354,
        9401, 9402, 9413, 9433, 9434, 9435, 9436, 9437, 9438,
    }
    if topic.knowledge_point_id in advanced_ids:
        return "advanced"
    if topic.knowledge_point_id in basic_ids:
        return "basic"
    return "medium"


def _curriculum_tier(topic: OpenSourceTopic) -> str:
    if 9337 <= topic.knowledge_point_id <= 9360 or 9433 <= topic.knowledge_point_id <= 9448:
        return "foundation_core"
    if _topic_difficulty(topic) == "advanced":
        return "advanced_extension"
    return "course_core"


if __name__ == "__main__":
    raise SystemExit(main())
