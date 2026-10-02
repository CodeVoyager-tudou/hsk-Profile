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
| `/shop/medicine`、`/shop/detail/:id` | 药品列表/详情。详情页为**京东式**：底部固定购买栏（数量步进 + 加入购物车 + 立即购买，不遮挡内容），点「立即购买」弹层选支付方式（**余额/积分**，当场校验资产是否充足——不足则置灰并写明"差 ￥x / 差 N 分"），提交后进收银台 |
| `/shop/cart` | 购物车：勾选多件合并结算成**一笔**订单（满减券门槛按合计判定）、失效商品置灰、选券与应付预估；底部导航带未结算件数角标 |
| `/shop/cashier/:orderId` | 收银台：PENDING 单的确认支付（余额扣余额/积分扣积分），30 分钟倒计时 + 超时自动关单展示 |
| `/points/signin`、`/points/records` | 每日签到（周循环）、积分流水 |
| `/coupons`、`/orders` | 领券/我的券、订单列表与取消（退券/退余额/退积分；CART 单展开每件明细） |
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
npm test        # vitest：19 个文件 / 164 用例（含兑换提醒、购物车结算、收银台超时、全局 toast 渲染回归）
```
