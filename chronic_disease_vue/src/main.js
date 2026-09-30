import { createApp } from 'vue'
import Cookies from 'js-cookie'

import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import locale from 'element-plus/es/locale/lang/zh-cn'

import '@/assets/styles/index.scss'

import App from './App'
import store from './store'
import router from './router'
import directive from './directive'
import plugins from './plugins'

import SvgIcon from '@/components/SvgIcon'

import { restoreAuth } from '@/store/demo'

import './permission'

import Pagination from '@/components/Pagination/index.vue'

const app = createApp(App)

app.component('SvgIcon', SvgIcon)
app.component('Pagination', Pagination)

app.use(router)
app.use(store)
app.use(plugins)
app.use(ElementPlus, {
  locale,
  size: Cookies.get('size') || 'default'
})

directive(app)

// 恢复业务视图层（store/demo.js）的登录态：与 Pinia user store 同源（登录时已镜像写入 localStorage）
restoreAuth()

app.mount('#app')
