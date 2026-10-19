export const DAILY_QUOTES = [
  { text: '面试不是闭卷考试，而是两个工程师在探讨具体的技术场景与边界。', category: '技术交流' },
  { text: '不熟悉的知识点，可以坦诚说明，再讲清你会怎样分析和验证。', category: '坦诚推演' },
  { text: '练习中暴露的缺口，是下一次复习的具体方向，不是对能力的定论。', category: '复盘心态' },
  { text: '讲清方案的约束、取舍与代价，比只报出一个技术名称更有说服力。', category: '方案权衡' },
  { text: '面试也是你了解团队的过程。关注合作方式，也关注彼此的期待。', category: '双向选择' },
  { text: '先给结论，再解释机制，最后补充边界与异常，让对方跟得上你的思路。', category: '结构表达' },
  { text: '项目经历不必堆满技术名词。讲清你解决了什么、为何这样做、结果如何。', category: '项目细节' },
  { text: '遇到追问，先确认对方关心的层次，再决定要展开到多深。', category: '回应追问' },
  { text: '回答前先澄清场景、规模与约束，能让后面的设计更有依据。', category: '澄清问题' },
  { text: '没有唯一最优的方案。把你的选择放回具体条件中，说明适用范围。', category: '边界意识' },
  { text: '一次卡顿不决定整场面试。停一下、理清问题，再继续表达。', category: '调整节奏' },
  { text: '复盘时先抓住一个可以改进的细节，下一次练习就有了明确目标。', category: '持续练习' },
] as const

export function getTodayQuote(offset = 0) {
  const now = new Date()
  const day = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
  let hash = 0
  for (const character of day) hash = (hash * 31 + character.charCodeAt(0)) >>> 0
  return DAILY_QUOTES[(hash + offset) % DAILY_QUOTES.length]!
}
