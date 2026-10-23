export function describeMode(mode: string | undefined) {
  switch (mode) {
    case 'LIVE': return { label: 'LIVE · 实时来源', hint: '按来源原时间展示，数据质量与使用许可另行校验。' }
    case 'CACHE': return { label: 'CACHE · 固定缓存', hint: '保留缓存原始数据与获取时间，缺失时不自动请求实时源。' }
    case 'REPLAY': return { label: 'REPLAY · 许可快照回放', hint: '仅使用可重放的固定输入；不会更新价格或请求实时源。' }
    case 'SYNTHETIC': return { label: 'SYNTHETIC · 合成演示', hint: '仅用于流程与公式演示，不代表真实行情研究。' }
    default: return { label: '尚无数据模式', hint: '数据模式未确认，不推断为实时数据。' }
  }
}
