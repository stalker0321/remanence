import react from '@vitejs/plugin-react'
export default { root: '/home/vodkolyan/projects/Remanence-release/web', plugins: [react()], logLevel: 'warn', server: { fs: { allow: ['..'] } } }
