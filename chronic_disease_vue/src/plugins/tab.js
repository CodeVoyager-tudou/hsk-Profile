export default {
  editableTabsValue: [],
  editableTabs: [],
  tabIndex: 0,
  addTab(view) {
    if (this.editableTabs.some(v => v.path === view.path)) return
    this.editableTabs.push(Object.assign({}, view, {
      title: view.meta.title || 'no-name'
    }))
    this.editableTabsValue = view.path
  },
  updateTab(view) {
    for (let i = 0; i < this.editableTabs.length; i++) {
      if (this.editableTabs[i].path === view.path) {
        Object.assign(this.editableTabs[i], view)
        if (view.meta && view.meta.title) {
          this.editableTabs[i].title = view.meta.title
        }
        break
      }
    }
  },
  deleteTab(view) {
    const tabsList = this.editableTabs
    let tabName = view.name
    if (this.editableTabsValue === view.path) {
      tabsList.forEach((tab, index) => {
        if (tab.name === tabName) {
          const nextTab = tabsList[index + 1] || tabsList[index - 1]
          if (nextTab) {
            this.editableTabsValue = nextTab.path
          }
        }
      })
    }
    this.editableTabs = tabsList.filter(tab => tab.name !== tabName)
  }
}
