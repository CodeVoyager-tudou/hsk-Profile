const cache = {
  session: {
    setJSON(key, val) {
      window.sessionStorage.setItem(key, JSON.stringify(val))
    },
    getJSON(key) {
      const value = window.sessionStorage.getItem(key)
      try {
        return JSON.parse(value)
      } catch (e) {
        return null
      }
    },
    remove(key) {
      window.sessionStorage.removeItem(key)
    },
    clear() {
      window.sessionStorage.clear()
    }
  },
  local: {
    setJSON(key, val) {
      window.localStorage.setItem(key, JSON.stringify(val))
    },
    getJSON(key) {
      const value = window.localStorage.getItem(key)
      try {
        return JSON.parse(value)
      } catch (e) {
        return null
      }
    },
    remove(key) {
      window.localStorage.removeItem(key)
    },
    clear() {
      window.localStorage.clear()
    }
  }
}

export default cache
