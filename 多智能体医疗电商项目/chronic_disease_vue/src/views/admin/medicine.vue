<template>
  <div class="admin-page">
    <AdminNav />
    <div class="page-pad">
      <div class="toolbar">
        <select v-model="statusFilter" class="in" @change="load(1)">
          <option value="">全部状态</option>
          <option value="1">上架中</option>
          <option value="0">已下架</option>
        </select>
        <input v-model="keyword" class="in" placeholder="药品名搜索" @keyup.enter="load(1)">
        <button class="btn" @click="load(1)">查询</button>
        <button class="btn primary" @click="openCreate">新增药品</button>
      </div>

      <div class="table-wrap">
        <table class="tb">
          <thead>
            <tr><th>ID</th><th>名称</th><th>价格</th><th>库存</th><th>状态</th><th>图片</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="m in rows" :key="m.id">
              <td>{{ m.id }}</td>
              <td class="ellipsis">{{ m.name }}</td>
              <td>￥{{ Number(m.price || 0).toFixed(2) }}</td>
              <td>{{ m.stock }}</td>
              <td :class="m.status === 1 ? 'ok' : 'off'">{{ m.status === 1 ? '上架' : '下架' }}</td>
              <td><span :class="m.imageUrl ? 'ok' : 'off'">{{ m.imageUrl ? '已传' : '无' }}</span></td>
              <td class="ops">
                <button class="btn" @click="onToggle(m)">{{ m.status === 1 ? '下架' : '上架' }}</button>
                <button class="btn" @click="openEdit(m)">编辑</button>
                <button class="btn" @click="fileInputs[m.id].click()">传图</button>
                <button class="btn warn" @click="askDelete(m)">删除</button>
                <input
                  :ref="(el) => (fileInputs[m.id] = el)"
                  type="file"
                  accept="image/*"
                  style="display: none"
                  @change="(e) => onImage(m, e)"
                >
              </td>
            </tr>
            <tr v-if="!rows.length"><td colspan="7" class="empty">无数据</td></tr>
          </tbody>
        </table>
      </div>
      <div class="pager">
        <button class="btn" :disabled="pageNum <= 1" @click="load(pageNum - 1)">上一页</button>
        <span>第 {{ pageNum }} 页</span>
        <button class="btn" :disabled="!hasMore" @click="load(pageNum + 1)">下一页</button>
      </div>
    </div>

    <!-- 新增/编辑药品：同一个弹窗两种模式。编辑只提交与初值不同的字段，新增只提交填了的字段。
         Esc 关闭（saving 中不关，与 closeEdit 守卫一致）—— 键盘用户不必去摸鼠标（复核 P2-7c） -->
    <div v-if="edit.show" class="dlg-mask" tabindex="-1" @click.self="closeEdit" @keydown.esc="closeEdit">
      <div class="dlg">
        <div class="dlg-head">
          {{ edit.mode === 'create' ? '新增药品' : '编辑药品' }}
          <span v-if="edit.mode === 'edit'" class="dlg-sub">#{{ edit.id }}</span>
          <button class="dlg-x" @click="closeEdit">×</button>
        </div>
        <div class="dlg-body">
          <div class="form-grid">
            <label>药品名称 *<input v-model="edit.form.name" class="in" maxlength="100" placeholder="必填"></label>
            <label>通用名<input v-model="edit.form.genericName" class="in" maxlength="100"></label>
            <label>分类<input v-model="edit.form.category" class="in" maxlength="50" placeholder="如：慢病用药"></label>
            <label>生产厂家<input v-model="edit.form.manufacturer" class="in" maxlength="200"></label>
            <label>现金价（元）*<input v-model="edit.form.price" class="in" type="number" step="0.01" min="0.01" placeholder="必填"></label>
            <label>积分兑换价（0=不可兑换）<input v-model="edit.form.pointsPrice" class="in" type="number" step="1" min="0"></label>
            <label>现金购买返积分<input v-model="edit.form.pointsReward" class="in" type="number" step="1" min="0"></label>
            <label>库存{{ edit.mode === 'create' ? '（留空按 0）' : ' *' }}<input v-model="edit.form.stock" class="in" type="number" step="1" min="0"></label>
          </div>
          <label class="block">适应症<textarea v-model="edit.form.indication" class="in ta" maxlength="2000" rows="2"></textarea></label>
          <label class="block">用法用量<textarea v-model="edit.form.dosage" class="in ta" maxlength="2000" rows="2"></textarea></label>
          <p class="hint">
            {{ edit.mode === 'create'
              ? '留空的字段按默认值处理（库存/积分为 0），新建即上架，可再点「下架」；图片在列表里传。'
              : '上下架与图片有各自的按钮，这里只管药品资料与价格；保存后会记入审计（谁改了什么）。' }}
          </p>
        </div>
        <div class="dlg-foot">
          <button class="btn" :disabled="edit.saving" @click="closeEdit">取消</button>
          <button class="btn primary" :disabled="edit.saving || !canSubmit"
                  :title="edit.mode === 'create' && !canSubmit ? '请先填写药品名称与现金价' : ''" @click="saveEdit">
            {{ submitLabel }}
          </button>
        </div>
      </div>
    </div>

    <!-- 删除确认：删除不可逆，先让管理员看清删的是哪一个（尤其同名药品）。
         初始焦点落在「取消」上 —— 不可逆操作的默认焦点不该在破坏性按钮（复核 P2-7c） -->
    <div v-if="remove.show" class="dlg-mask" tabindex="-1" @click.self="remove.show = false" @keydown.esc="remove.show = false">
      <div class="dlg narrow">
        <div class="dlg-head">删除药品<button class="dlg-x" @click="remove.show = false">×</button></div>
        <div class="dlg-body">
          <p class="del-text">
            确定删除「<b>{{ remove.name }}</b>」（#{{ remove.id }}）吗？此操作不可恢复。
          </p>
          <p class="hint">
            有历史订单或秒杀活动引用的药品会被拒绝并提示改用「下架」——
            订单靠 medicine_id 关联，删掉会让历史与统计断链。
          </p>
        </div>
        <div class="dlg-foot">
          <button ref="removeCancelBtn" class="btn" :disabled="remove.saving" @click="remove.show = false">取消</button>
          <button class="btn warn solid" :disabled="remove.saving" @click="confirmDelete">
            {{ remove.saving ? '删除中…' : '确认删除' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useStore } from '@/store/demo'
import {
  buildCreatePayload,
  buildMedicinePayload,
  changedFields,
  emptyMedicineForm,
  formOfMedicine,
  validateCreatePayload,
  validateMedicinePayload,
} from '@/utils/medicineEdit'
import AdminNav from './components/AdminNav.vue'

const router = useRouter()
const {
  state, toast,
  adminMedicinePage, adminMedicineUpdate, adminMedicineCreate, adminMedicineDelete,
  adminMedicineStatus, adminMedicineImage,
} = useStore()

const rows = ref([])
const pageNum = ref(1)
const hasMore = ref(false)
const statusFilter = ref('')
const keyword = ref('')
const fileInputs = reactive({})

// 新增与编辑共用一个弹窗：mode 决定提交方式（新增=只发填了的字段；编辑=只发与初值不同的字段）
const edit = reactive({ show: false, saving: false, mode: 'edit', id: null, name: '', origin: {}, form: {} })
// 删除确认（不可逆，单独一个确认框，避免误点）
const remove = reactive({ show: false, saving: false, id: null, name: '' })
// 确认框打开时把初始焦点放在「取消」上：键盘用户直接 Enter=安全动作，
// 想删的人必须有意识地 Tab 一次（复核 P2-7c）
const removeCancelBtn = ref(null)
watch(() => remove.show, (show) => {
  if (show) nextTick(() => removeCancelBtn.value && removeCancelBtn.value.focus())
})

function guard() {
  if (state.role !== 'ADMIN') {
    toast('需要管理员账号（admin）登录')
    router.push('/index')
    return false
  }
  return true
}

async function load(p) {
  pageNum.value = p
  const r = await adminMedicinePage(p, statusFilter.value, keyword.value.trim())
  if (!r.ok) {
    toast(r.msg)
    return
  }
  rows.value = r.data.records || []
  hasMore.value = (r.data.current || p) < (r.data.pages || 1)
}

async function onToggle(m) {
  const target = m.status === 1 ? 0 : 1
  const r = await adminMedicineStatus(m.id, target)
  toast(r.ok ? `已${target === 1 ? '上架' : '下架'}：${m.name}` : r.msg)
  if (r.ok) load(pageNum.value)
}

/** 打开编辑弹窗：以当前行做初值快照，后续提交只发与它的差异 */
function openEdit(m) {
  const origin = formOfMedicine(m)
  edit.mode = 'edit'
  edit.id = m.id
  edit.name = m.name
  edit.origin = { ...origin }
  edit.form = { ...origin }
  edit.saving = false
  edit.show = true
}

/** 打开新增弹窗：空表单（初值全空，所以"填了的字段"就是提交内容） */
function openCreate() {
  const empty = emptyMedicineForm()
  edit.mode = 'create'
  edit.id = null
  edit.name = ''
  edit.origin = empty
  edit.form = { ...empty }
  edit.saving = false
  edit.show = true
}

function closeEdit() {
  if (edit.saving) return
  edit.show = false
}

/** 变更项（编辑模式用于按钮文案与提交内容） */
const pendingChanges = computed(() => changedFields(edit.origin, edit.form))

/** 新增模式：必填项（名称+现金价）没填完就禁用保存按钮 —— 按钮状态即校验状态（复核 P2-7a）；
 *  编辑模式：没有任何变更时禁用 */
const canSubmit = computed(() => {
  if (edit.mode === 'create') {
    return String(edit.form.name || '').trim() !== '' && String(edit.form.price || '').trim() !== ''
  }
  return pendingChanges.value.length > 0
})

const submitLabel = computed(() => {
  if (edit.saving) {
    return edit.mode === 'create' ? '新增中…' : '保存中…'
  }
  if (edit.mode === 'create') {
    return '新增'
  }
  return pendingChanges.value.length ? `保存（${pendingChanges.value.length} 项变更）` : '无变更'
})

async function saveEdit() {
  if (edit.saving) return
  if (edit.mode === 'create') {
    await submitCreate()
    return
  }
  if (!pendingChanges.value.length) return
  const { payload, error } = buildMedicinePayload(edit.origin, edit.form)
  if (error) {
    toast(error)
    return
  }
  const invalid = validateMedicinePayload(payload, edit.form)
  if (invalid) {
    toast(invalid)
    return
  }
  const count = pendingChanges.value.length
  edit.saving = true
  const r = await adminMedicineUpdate(edit.id, payload)
  edit.saving = false
  if (!r.ok) {
    toast(r.msg)
    return
  }
  toast(`已保存「${edit.name}」的 ${count} 项修改，已记入审计`)
  edit.show = false
  load(pageNum.value)
}

async function submitCreate() {
  const { payload, error } = buildCreatePayload(edit.form)
  if (error) {
    toast(error)
    return
  }
  const invalid = validateCreatePayload(payload, edit.form)
  if (invalid) {
    toast(invalid)
    return
  }
  edit.saving = true
  const r = await adminMedicineCreate(payload)
  edit.saving = false
  if (!r.ok) {
    toast(r.msg)
    return
  }
  toast(`已新增「${r.data.name}」（#${r.data.id}），已记入审计`)
  edit.show = false
  // 新建的行在列表第一页（列表按 id 倒序），直接回第一页让管理员看到它
  load(1)
}

function askDelete(m) {
  remove.id = m.id
  remove.name = m.name
  remove.saving = false
  remove.show = true
}

async function confirmDelete() {
  if (remove.saving) return
  remove.saving = true
  const r = await adminMedicineDelete(remove.id)
  remove.saving = false
  remove.show = false
  // 被订单/活动引用时后端会拒绝并说明原因，这里原样透出，不自己编话术
  toast(r.ok ? `已删除「${remove.name}」` : r.msg)
  if (r.ok) load(pageNum.value)
}

async function onImage(m, e) {
  const file = e.target.files && e.target.files[0]
  e.target.value = ''
  if (!file) return
  const r = await adminMedicineImage(m.id, file)
  toast(r.ok ? '图片已上传 OSS 并回填' : r.msg)
  if (r.ok) load(pageNum.value)
}

onMounted(() => {
  if (guard()) load(1)
})
</script>

<style scoped src="./admin.css"></style>
