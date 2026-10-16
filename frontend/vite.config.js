import { mergeConfig } from 'vite'
import original from './vite.config.ts'
import { extendEntry } from './src/v2/entry-plugin.ts'

export default mergeConfig(original, {
  plugins: [{ name: 'interview-v2-entry', transformIndexHtml: { order: 'pre', handler: extendEntry } }],
})
