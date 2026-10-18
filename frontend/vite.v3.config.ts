import { mergeConfig } from 'vite'
import original from './vite.config.js'
import { extendV3Entry } from './src/v3/entry-plugin'

export default mergeConfig(original, {
  plugins: [{ name: 'interview-v3-entry', transformIndexHtml: { order: 'pre', handler: extendV3Entry } }],
})
