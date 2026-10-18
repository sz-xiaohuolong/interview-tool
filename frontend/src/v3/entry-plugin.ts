export function extendV3Entry(html: string) {
  const entry = /src=(['"])\/src\/v2\/main\.ts\1/g
  if ([...html.matchAll(entry)].length !== 1) throw new Error('V3 需要唯一的 V2 应用入口，请核对追加配置顺序。')
  return html.replace(entry, 'src="/src/v3/main.ts"')
}
