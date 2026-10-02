import auth from './auth'
import cache from './cache'
import modal from './modal'
import tab from './tab'
import download from './download'

export default {
  install(app) {
    app.config.globalProperties.$modal = modal
    app.config.globalProperties.$tabs = tab
    app.config.globalProperties.$auth = auth
    app.config.globalProperties.$cache = cache
    app.config.globalProperties.$download = download
  }
}
