import react from '@vitejs/plugin-react'
import { ortWasm } from './tools/ort-wasm.mjs'
export default { root: '/home/vodkolyan/projects/Remanence-release/web', plugins: [react(), ortWasm()], logLevel: 'warn', server: { fs: { allow: ['..'] } } }
