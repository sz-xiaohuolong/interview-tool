export function extendEntry(html: string) {
  const entry = /src=(['"])\/src\/main\.ts\1/g
  if ([...html.matchAll(entry)].length !== 1) throw new Error('原应用入口必须且只能有一个，请核对 index.html。')
  return html.replace(entry, 'src="/src/v2/main.ts"')
}
