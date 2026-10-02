// 演示兜底开关。
//
// 背景：原先前端在“后端不可达”时会自动切到 mock 数据/演示回答，生产环境下一旦后端故障，
// 用户看到的会是“看起来正常”的假数据，故障被掩盖。现在改为显式开关：
//   - 生产构建 .env.production 里 VITE_ENABLE_DEMO=false（默认），后端不可达时如实报错；
//   - 本地演示/离线录屏时设 VITE_ENABLE_DEMO=true（.env.development 已开）；
//   - 单测环境（MODE=test）保持开启，避免影响既有用例。
const flag = import.meta.env.VITE_ENABLE_DEMO

export const DEMO_ENABLED = flag === 'true' || import.meta.env.MODE === 'test'

export const DEMO_DISABLED_MESSAGE = '后端服务不可用，请稍后重试'
