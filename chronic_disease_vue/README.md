# chronic_disease_vue（前端 · Vue3 + Vite）

慢性病健康管理平台前端：药品商城、积分签到、优惠券、订单、AI 问诊、个人中心。
**真实后端优先、离线自动降级 mock**，因此不依赖后端也能完整演示界面。

配套仓库：AI 服务 `chronic_disease_ai`、微服务 `chronic_disease`。

## 启动

```bash
npm install
npm run dev        # http://localhost:5173
```
开发代理：`/dev-api` → `http://localhost:8080`（见 `vite.config.js`），因此需先起 Java 网关。
构建：`npm run build:prod`；测试：`npm test`（vitest）。

## 数据层（重要）

业务页面统一使用 `src/store/demo.js` 这一轻量响应式数据层（fetch，非 axios）：

- 请求后端成功后用真实数据；**任一次失败即切换 mock 模式**（`src/utils/mock.js`），
  保证离线也能演示，并 toast「后端未连接，已切换演示数据」；
- 登录/注册同样"真实后端优先"（`/api/user/login`、`/api/user/register`），
  后端不可达时降级为本地演示账号（`demo/123456`、`admin/admin`，仅离线模式有效）；
- 登录态真源是 Pinia user store，登录成功后镜像到 demo store（`syncLegacyAuth`）供页面读取。
- 全局提示：`src/layout/index.vue` 的 toast 浮层绑定 `state.toastMsg / state.toastVisible`。

## 页面

| 路由 | 说明 |
| --- | --- |
| `/shop/medicine`、`/shop/detail/:id` | 药品列表/详情，现金购买、积分兑换（积分不足会提示"兑换失败：积分余额不足"） |
| `/points/signin`、`/points/records` | 每日签到（周循环）、积分流水 |
| `/coupons`、`/orders` | 领券/我的券、订单列表与取消（退券/退积分） |
| `/ai` | AI 问诊（WebSocket 真流式优先，HTTP 兜底；多轮会话） |
| `/user/profile`、`/login`、`/register` | 个人中心、登录、注册 |

## 目录

```
src/
  store/demo.js        # 业务数据层（真实后端 + mock 降级、toast）
  store/modules/       # Pinia：user/app/settings/dict/tagsView
  utils/               # request(axios 拦截器/错误码)、auth、mock、chat、format…
  api/                 # axios 接口封装（login/logout/refresh）
  layout/index.vue     # 移动端布局 + 全局 toast + 底部导航
  views/               # shop / points / user / ai / login / dashboard
  store/__tests__、layout/__tests__、utils/__tests__ …   # vitest 用例
```

## 测试

```bash
npm test        # vitest：13 个文件 / 114 用例（含兑换提醒、全局 toast 渲染回归）
```
