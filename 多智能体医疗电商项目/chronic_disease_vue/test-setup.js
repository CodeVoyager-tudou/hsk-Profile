import { vi } from 'vitest'
import { defineStore } from 'pinia'

// Make defineStore available globally (mimics unplugin-auto-import)
globalThis.defineStore = defineStore
