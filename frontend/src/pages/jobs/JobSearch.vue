<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { Check, ChevronDown, Search, X } from 'lucide-vue-next'
import { errorMessage } from '../../api'
import { dateTime } from '../../format'
import { jobSearch, jobSources, sourceLabel, type Favorite, type JobRecord, type JobSource, type JobSourceLink, type SaveJdInput, type SourceProgress, type TaskSnapshot } from '../../api/job-search'
import { deduplicateJobs, defaultFilters, groupJobs, matchesJob, originalOptions, sourceDifference, type JobGroup } from './job-search-model'
import Notice from '../../components/common/Notice.vue'
import EmptyState from '../../components/common/EmptyState.vue'
import LoadingState from '../../components/common/LoadingState.vue'
import Modal from '../../components/common/Modal.vue'

type Tab = 'search' | 'favorites'
type Row = JobGroup & { representative: JobRecord; favorite?: Favorite }
const router = useRouter(), route = useRoute()
const tab = ref<Tab>(route.query.tab === 'favorites' ? 'favorites' : 'search')
const page = ref<HTMLElement>(), workspace = ref<HTMLElement>(), list = ref<HTMLElement>(), detail = ref<HTMLElement>(), cityField = ref<HTMLElement>()
const dual = ref(false), workspaceHeight = ref(500), filtersOpen = ref(false), openingSingleDetail = ref(false)
const cities = ref<{ id: string; name: string }[]>([]), citiesLoading = ref(true), citiesError = ref('')
const keyword = ref(''), cityInput = ref(''), selectedSources = ref<JobSource[]>(jobSources.map(item => item.value))
const cityOpen = ref(false), cityActiveIndex = ref(0), suppressCityFocus = ref(false)
const city = computed(() => cities.value.find(item => item.name === cityInput.value.trim()))
const filteredCities = computed(() => {
  const query = cityInput.value.trim().toLocaleLowerCase()
  return query ? cities.value.filter(item => item.name.toLocaleLowerCase().includes(query)) : cities.value
})
const cityQuickPicks = computed(() => cities.value.slice(0, 4))
const task = ref<TaskSnapshot | null>(null), creating = ref(false), actionBusy = ref(false), taskError = ref(''), validation = ref(''), notice = ref('')
const favorites = ref<Favorite[]>([]), favoritesLoading = ref(true), favoritesError = ref(''), favoritesReady = ref(false)
const filters = ref(defaultFilters()), salaryMin = ref(''), salaryMax = ref(''), salaryError = ref('')
const experienceOptions = ref<string[]>([]), educationOptions = ref<string[]>([])
const selection = ref<Record<Tab, string | null>>({ search: null, favorites: null })
const selectedKey = computed(() => selection.value[tab.value])
const mobileDetail = computed(() => !dual.value && (!!route.query.job || openingSingleDetail.value))
const detailStates = ref<Record<string, { loading: boolean; error: string }>>({})
const details = ref<Record<string, JobRecord>>({})
const links = ref<Record<string, JobSourceLink | null>>({}), linkStates = ref<Record<string, 'loading' | 'ready' | 'error'>>({})
const favoriteBusy = ref<Record<string, boolean>>({}), favoriteErrors = ref<Record<string, string>>({})
const cancelTarget = ref<Favorite | null>(null), cancelBusy = ref(false), cancelError = ref('')
const jdOpen = ref(false), jdBusy = ref(false), jdError = ref(''), jdName = ref(''), jdContent = ref('')
const jdContext = ref<{ job: JobRecord; taskId?: string; favoriteId?: string; initialName: string; initialContent: string } | null>(null)
let epoch = 0, searchIdentity = 0, lifecycle = 0, pollTimer: ReturnType<typeof setTimeout> | undefined, left = false, firstSearchResult = false, firstFavoriteResult = false
let observer: ResizeObserver | undefined
const firstSeen = new Map<string, number>()
const listWorkspaceTop: Record<Tab, number> = { search: 360, favorites: 360 }
const scroll = { search: { page: 0, list: 0, detail: 0 }, favorites: { page: 0, list: 0, detail: 0 } }
const focusKeys: Record<Tab, string | null> = { search: null, favorites: null }
const allJobs = computed(() => deduplicateJobs((task.value?.jobs || []).map(job => details.value[job.key] || job)))
const searchRows = computed<Row[]>(() => groupJobs(allJobs.value).flatMap(group => {
  const representative = group.sources.filter(job => matchesJob(job, filters.value)).sort((a, b) => (firstSeen.get(a.key) ?? Infinity) - (firstSeen.get(b.key) ?? Infinity))[0]
  return representative ? [{ ...group, id: representative.key, representative }] : []
}))
const favoriteRows = computed<Row[]>(() => [...favorites.value].sort((a, b) => b.createdAt.localeCompare(a.createdAt) || a.id.localeCompare(b.id)).filter(item => item.sources.length).map(item => ({ id: item.id, sources: item.sources, representative: item.sources[0]!, favorite: item })))
const rows = computed(() => tab.value === 'search' ? searchRows.value : favoriteRows.value)
const selectedRow = computed(() => rows.value.find(row => row.sources.some(job => job.key === selectedKey.value)))
const selectedJob = computed(() => selectedRow.value?.sources.find(job => job.key === selectedKey.value) || null)
const currentDetailState = computed(() => selectedJob.value ? detailStates.value[selectedJob.value.key] : undefined)
const currentLinkState = computed(() => selectedJob.value ? linkStates.value[selectedJob.value.key] : undefined)
const running = computed(() => task.value?.sources.some(item => item.status === 'SEARCHING') || false)
const changed = computed(() => !!task.value && (keyword.value !== task.value.keyword || city.value?.id !== task.value.cityId || selectedSources.value.join(',') !== task.value.sources.map(item => item.source).join(',')))
const filterCount = computed(() => Number(!!filters.value.source) + Number(filters.value.minK != null || filters.value.maxK != null) + Number(!!filters.value.experience) + Number(!!filters.value.education) + Number(!filters.value.unknownSalary) + Number(!filters.value.unknownExperience) + Number(!filters.value.unknownEducation))
const canMore = computed(() => task.value?.status !== 'STOPPED' && task.value?.sources.some(item => item.hasMore && item.count < 100 && item.status === 'READY'))
const linkKeys = computed(() => [...new Set([...allJobs.value.map(job => job.key), ...favorites.value.flatMap(item => item.sources.map(job => job.key))])])

function measure() {
  if (!page.value) return
  const width = page.value.clientWidth ? page.value.clientWidth - parseFloat(getComputedStyle(page.value).paddingLeft || '0') - parseFloat(getComputedStyle(page.value).paddingRight || '0') : window.innerWidth - 280
  // 单列详情会隐藏条件区，沿用列表起点，避免隐藏后又被误判为双栏。
  if (!mobileDetail.value && workspace.value) listWorkspaceTop[tab.value] = workspace.value.getBoundingClientRect().top + window.scrollY
  const available = window.innerHeight - listWorkspaceTop[tab.value] - 24
  workspaceHeight.value = Math.max(360, available)
  const nextDual = width >= 960 && available >= 360
  const activelyReading = selectedRow.value?.sources.some(job => job.key === focusKeys[tab.value])
  if (dual.value && !nextDual && activelyReading && selectedJob.value && detail.value && !route.query.job) {
    // 保留正在阅读的详情及其 DOM，成功提示或视口变化不能把操作入口卸载。
    rememberScroll(); focusKeys[tab.value] = selectedJob.value.key; openingSingleDetail.value = true
    void router.replace({ path: '/job-search', query: { ...(tab.value === 'favorites' ? { tab: 'favorites' } : {}), job: selectedJob.value.key } }).finally(() => { openingSingleDetail.value = false })
  }
  dual.value = nextDual
}
function rememberScroll() { scroll[tab.value] = { page: window.scrollY, list: list.value?.scrollTop || 0, detail: detail.value?.scrollTop || 0 } }
async function restoreScroll(value: Tab, focus = false) {
  await nextTick()
  if (list.value) list.value.scrollTop = scroll[value].list
  if (detail.value) detail.value.scrollTop = scroll[value].detail
  window.scrollTo?.({ top: scroll[value].page, behavior: 'instant' })
  if (focus) Array.from(document.querySelectorAll<HTMLElement>('[data-job-select]')).find(item => item.dataset.jobSelect === focusKeys[value])?.focus({ preventScroll: true })
}
async function setTab(value: Tab) {
  if (tab.value === value) return
  rememberScroll(); tab.value = value
  await router.replace({ path: '/job-search', query: value === 'favorites' ? { tab: 'favorites' } : {} })
  await restoreScroll(value)
}
function tabKey(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault(); const value = event.key === 'Home' ? 'search' : event.key === 'End' ? 'favorites' : tab.value === 'search' ? 'favorites' : 'search'
  void setTab(value); nextTick(() => document.getElementById(`jobs-tab-${value}`)?.focus())
}
async function choose(row: Row) {
  rememberScroll(); selection.value[tab.value] = row.representative.key; focusKeys[tab.value] = row.representative.key
  if (!dual.value) { await router.push({ path: '/job-search', query: { ...(tab.value === 'favorites' ? { tab: 'favorites' } : {}), job: row.representative.key } }); await nextTick(); window.scrollTo?.({ top: 0, behavior: 'instant' }); document.getElementById('job-detail-title')?.focus() }
}
function chooseSource(key: string) { focusKeys[tab.value] = selectedRow.value?.representative.key || null; selection.value[tab.value] = key }
async function backToList() {
  await router.replace({ path: '/job-search', query: tab.value === 'favorites' ? { tab: 'favorites' } : {} })
  await restoreScroll(tab.value, true)
}
function clearFilters() { filters.value = defaultFilters(); salaryMin.value = ''; salaryMax.value = ''; salaryError.value = '' }
function commitSalary() {
  const min = salaryMin.value.trim() ? Number(salaryMin.value) : null, max = salaryMax.value.trim() ? Number(salaryMax.value) : null
  if ((min != null && (!Number.isFinite(min) || min < 0)) || (max != null && (!Number.isFinite(max) || max < 0)) || (min != null && max != null && min > max)) { salaryError.value = '月薪须为非负数，且下限不能高于上限；仍保留上一次有效筛选。'; return }
  salaryError.value = ''; filters.value.minK = min; filters.value.maxK = max
}
function stopPolling() { if (pollTimer) clearTimeout(pollTimer); pollTimer = undefined }
function valid(id: string, generation: number) { return !left && epoch === generation && task.value?.id === id }
function schedulePoll() {
  stopPolling(); if (!task.value || !running.value || left) return
  const id = task.value.id, generation = epoch
  pollTimer = setTimeout(async () => {
    try { const result = await jobSearch.task(id); if (!valid(id, generation)) return; applyTask(result); schedulePoll() }
    catch (error) { if (valid(id, generation)) taskError.value = errorMessage(error) }
  }, 900)
}
function applyTask(value: TaskSnapshot) {
  if (task.value?.id === value.id && value.revision < task.value.revision) return
  for (const job of value.jobs) { const key = `${job.source}:${job.sourceId}`; if (!firstSeen.has(key)) firstSeen.set(key, firstSeen.size) }
  task.value = { ...value, jobs: deduplicateJobs(value.jobs) }
  if (!firstSearchResult && allJobs.value.length) {
    firstSearchResult = true
    if (dual.value) selection.value.search = searchRows.value[0]?.representative.key || null
  }
  nextTick(measure)
}
async function search() {
  if (creating.value) return
  validation.value = ''
  if (!keyword.value.trim()) { validation.value = '请填写岗位关键词。'; document.getElementById('job-keyword')?.focus(); return }
  if (!city.value) { validation.value = '请选择支持的城市，可输入城市名称查找。'; document.getElementById('job-city')?.focus(); return }
  if (!selectedSources.value.length) { validation.value = '请至少选择一个搜索平台。'; document.getElementById('job-search-platform-BOSS')?.focus(); return }
  const previous = task.value?.id, generation = ++epoch, input = { keyword: keyword.value, cityId: city.value.id, sources: [...selectedSources.value] }
  searchIdentity++
  stopPolling(); if (previous) void jobSearch.stop(previous).catch(() => {})
  task.value = null; firstSeen.clear(); experienceOptions.value = []; educationOptions.value = []; details.value = {}; detailStates.value = {}; selection.value.search = null; focusKeys.search = null; firstSearchResult = false; clearFilters(); taskError.value = ''; notice.value = ''; creating.value = true; actionBusy.value = false
  try { localStorage.setItem('job-search:draft', JSON.stringify(input)) } catch { /* 存储不可用时仍可搜索。 */ }
  if (route.query.job) await router.replace({ query: {} })
  try {
    const result = await jobSearch.start(input)
    if (left || generation !== epoch) { void jobSearch.stop(result.id).catch(() => {}); return }
    applyTask(result); schedulePoll()
  } catch (error) { if (!left && generation === epoch) taskError.value = errorMessage(error) }
  finally { if (generation === epoch) creating.value = false }
}
async function taskAction(action: 'more' | 'retry', source?: JobSource) {
  if (!task.value || actionBusy.value || task.value.status === 'STOPPED' || (action === 'more' && (running.value || !canMore.value))) return
  const id = task.value.id, generation = ++epoch; stopPolling(); actionBusy.value = true; taskError.value = ''
  try { const result = action === 'more' ? await jobSearch.more(id) : await jobSearch.retry(id, source!); if (!valid(id, generation)) return; applyTask(result); schedulePoll() }
  catch (error) { if (valid(id, generation)) taskError.value = errorMessage(error) }
  finally { if (valid(id, generation)) actionBusy.value = false }
}
async function stopSearch() {
  if (!task.value) return
  const id = task.value.id, generation = ++epoch; stopPolling(); actionBusy.value = true; taskError.value = ''
  task.value = { ...task.value, status: 'STOPPED', sources: task.value.sources.map(source => ({ ...source, status: source.status === 'SEARCHING' ? 'STOPPED' : source.status })) }
  try { await jobSearch.stop(id) }
  catch (error) { if (valid(id, generation)) taskError.value = `停止请求未确认：${errorMessage(error)}；本页已停止接收结果，可重试停止。` }
  finally { if (valid(id, generation)) actionBusy.value = false }
}
async function ensureDetail(job: JobRecord, retry = false) {
  if (tab.value !== 'search' || !task.value || job.description?.trim() || detailStates.value[job.key]?.loading || (!retry && detailStates.value[job.key])) return
  const id = task.value.id, generation = searchIdentity, key = job.key
  const current = () => !left && generation === searchIdentity && task.value?.id === id
  detailStates.value[key] = { loading: true, error: '' }
  try {
    const result = await jobSearch.detail(id, key)
    if (!current()) return
    if (result.key !== key || result.source !== job.source || result.sourceId !== job.sourceId) throw new Error('岗位正文来源不一致，请重试。')
    details.value[key] = result
  } catch (error) { if (current()) detailStates.value[key] = { loading: false, error: errorMessage(error) } }
  finally { if (current() && detailStates.value[key]) detailStates.value[key]!.loading = false }
}
async function loadCities() {
  citiesLoading.value = true; citiesError.value = ''
  try {
    cities.value = await jobSearch.cities()
    try {
      const draft = JSON.parse(localStorage.getItem('job-search:draft') || 'null')
      if (draft) { keyword.value = typeof draft.keyword === 'string' ? draft.keyword : ''; cityInput.value = cities.value.find(item => item.id === draft.cityId)?.name || ''; if (Array.isArray(draft.sources)) selectedSources.value = draft.sources.filter((source: JobSource) => jobSources.some(item => item.value === source)) }
    } catch { /* 无效条件不恢复。 */ }
  } catch (error) { citiesError.value = errorMessage(error) }
  finally { citiesLoading.value = false }
}
async function loadFavorites() {
  const generation = lifecycle; favoritesLoading.value = true; favoritesError.value = ''
  try { const result = await jobSearch.favorites(); if (generation !== lifecycle) return; favorites.value = result; favoritesReady.value = true
    if (!firstFavoriteResult && result.length) { firstFavoriteResult = true; if (dual.value) selection.value.favorites = favoriteRows.value[0]?.representative.key || null }
    restoreRouteSelection()
  } catch (error) { if (generation === lifecycle) favoritesError.value = errorMessage(error) }
  finally { if (generation === lifecycle) favoritesLoading.value = false }
}
async function lookup(keys: string[]) {
  if (!keys.length) return
  const generation = lifecycle
  for (const key of keys) linkStates.value[key] = 'loading'
  try {
    const result = await jobSearch.lookup(keys); if (generation !== lifecycle) return
    for (const key of keys) {
      if (Object.prototype.hasOwnProperty.call(result, key)) { links.value[key] = result[key] ?? null; linkStates.value[key] = 'ready' }
      else linkStates.value[key] = 'error'
    }
  } catch { if (generation === lifecycle) for (const key of keys) linkStates.value[key] = 'error' }
}
function favoriteFor(row: JobGroup) { return favorites.value.find(item => item.sources.some(source => row.sources.some(job => job.key === source.key))) }
function favoriteCount(row: JobGroup) { return row.sources.filter(job => favorites.value.some(item => item.sources.some(source => source.key === job.key))).length }
function favoriteLabel(row: JobGroup) { const count = favoriteCount(row); return favoriteBusy.value[row.id] ? '收藏中…' : count === row.sources.length ? `已收藏 ${count}/${row.sources.length} 来源` : count ? `已收藏 ${count}/${row.sources.length} 来源 · 补充收藏` : row.sources.length > 1 ? `收藏 ${row.sources.length} 个来源` : '收藏' }
async function favorite(row: JobGroup) {
  if (!task.value || !favoritesReady.value || favoriteBusy.value[row.id]) return
  const existing = favoriteFor(row), jobKeys = row.sources.filter(job => !favorites.value.some(item => item.sources.some(source => source.key === job.key))).map(job => job.key)
  if (!jobKeys.length) { if (existing) askCancel(existing); return }
  const generation = lifecycle; favoriteBusy.value[row.id] = true; favoriteErrors.value[row.id] = ''
  try { const result = await jobSearch.favorite({ taskId: task.value.id, jobKeys, ...(existing ? { favoriteId: existing.id } : {}) }); if (generation !== lifecycle) return
    const index = favorites.value.findIndex(item => item.id === result.id); if (index < 0) favorites.value.push(result); else favorites.value[index] = result
  } catch (error) { if (generation === lifecycle) favoriteErrors.value[row.id] = errorMessage(error) }
  finally { if (generation === lifecycle) favoriteBusy.value[row.id] = false }
}
function askCancel(item: Favorite) { cancelTarget.value = item; cancelError.value = '' }
async function removeFavorite() {
  if (!cancelTarget.value || cancelBusy.value) return
  const id = cancelTarget.value.id, index = favoriteRows.value.findIndex(row => row.id === id); cancelBusy.value = true; cancelError.value = ''
  try { await jobSearch.removeFavorite(id); favorites.value = favorites.value.filter(item => item.id !== id); cancelTarget.value = null
    if (selection.value.favorites && !favoriteRows.value.some(row => row.sources.some(job => job.key === selection.value.favorites))) { selection.value.favorites = null; if (tab.value === 'favorites' && route.query.job) await backToList() }
    await nextTick(); if (tab.value === 'favorites') { const row = favoriteRows.value[Math.min(index, favoriteRows.value.length - 1)]; if (row) Array.from(document.querySelectorAll<HTMLElement>('[data-job-select]')).find(item => item.dataset.jobSelect === row.representative.key)?.focus(); else document.getElementById('jobs-go-search')?.focus() }
  } catch (error) { cancelError.value = errorMessage(error) }
  finally { cancelBusy.value = false }
}
async function jdAction() {
  const job = selectedJob.value, row = selectedRow.value
  if (!job || !row || linkStates.value[job.key] !== 'ready') return
  focusKeys[tab.value] = row.representative.key
  const origin = { job: { ...job }, ...(tab.value === 'search' ? { taskId: task.value!.id } : { favoriteId: row.favorite!.id }) }
  await lookup([job.key])
  if (left || linkStates.value[job.key] !== 'ready') return
  const saved = links.value[job.key]
  if (saved) { await router.push({ path: '/materials', query: { kind: 'JD', materialId: saved.materialId } }); return }
  const name = job.company ? `${job.company}－${job.title}` : job.title, content = job.description || ''
  jdContext.value = { ...origin, initialName: name, initialContent: content }; jdName.value = name; jdContent.value = content; jdError.value = ''; jdOpen.value = true
}
function closeJd(open: boolean) {
  if (open || jdBusy.value) return
  if (jdContext.value && (jdName.value !== jdContext.value.initialName || jdContent.value !== jdContext.value.initialContent) && !window.confirm('存在尚未保存的修改，确定放弃修改并关闭？')) return
  jdOpen.value = false
}
async function saveJd() {
  const context = jdContext.value
  if (!context || jdBusy.value) return
  jdError.value = ''
  if (!jdName.value.trim() || jdName.value.length > 120) { jdError.value = jdName.value.length > 120 ? `名称超出 ${jdName.value.length - 120} 个字符，请修改后保存。` : '请填写 JD 名称。'; return }
  if (!jdContent.value.trim() || jdContent.value.length > 24000) { jdError.value = jdContent.value.length > 24000 ? `正文超出 ${jdContent.value.length - 24000} 个字符，请修改后保存。` : '请填写 JD 正文。'; return }
  jdBusy.value = true
  try {
    const input: SaveJdInput = { ...(context.taskId ? { taskId: context.taskId } : { favoriteId: context.favoriteId }), jobKey: context.job.key, name: jdName.value, content: jdContent.value }
    const result = await jobSearch.saveJd(input)
    links.value[context.job.key] = { ...result.source, materialId: result.materialId }; linkStates.value[context.job.key] = 'ready'; jdOpen.value = false; notice.value = '已保存到目标 JD，可通过“查看已保存 JD”打开。'
  } catch (error) { jdError.value = errorMessage(error) }
  finally { jdBusy.value = false }
}
const progressLabels: Record<SourceProgress['status'], string> = { SEARCHING: '搜索中', READY: '本批已获取', EMPTY: '本次没有岗位', FAILED: '暂不可用', ENDED: '已获取本次可用结果', LIMIT: '已达本次获取上限', STOPPED: '已停止' }
function progressText(progress: SourceProgress) { return `${progressLabels[progress.status]}${progress.status === 'SEARCHING' && !progress.count ? '' : ` · 已获取 ${progress.count} 条`}` }
function focusKeyword() { document.getElementById('job-keyword')?.focus() }
function openCityPicker() { if (suppressCityFocus.value) { suppressCityFocus.value = false; return }; if (!citiesLoading.value && !citiesError.value) { if (!cityOpen.value) cityActiveIndex.value = Math.max(0, filteredCities.value.findIndex(item => item.name === cityInput.value.trim())); cityOpen.value = true } }
function closeCityPicker() { cityOpen.value = false; cityActiveIndex.value = 0 }
function selectCity(value: { id: string; name: string }) { cityInput.value = value.name; closeCityPicker(); suppressCityFocus.value = true; nextTick(() => document.getElementById('job-city')?.focus()) }
function clearCity() { cityInput.value = ''; cityOpen.value = true; cityActiveIndex.value = 0; nextTick(() => document.getElementById('job-city')?.focus()) }
function cityInputChanged() { cityActiveIndex.value = 0; openCityPicker() }
function cityKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { if (cityOpen.value) { event.preventDefault(); closeCityPicker() }; return }
  if (event.key === 'ArrowDown') { event.preventDefault(); openCityPicker(); if (filteredCities.value.length) cityActiveIndex.value = Math.min(cityActiveIndex.value + 1, filteredCities.value.length - 1); return }
  if (event.key === 'ArrowUp') { event.preventDefault(); openCityPicker(); if (filteredCities.value.length) cityActiveIndex.value = Math.max(cityActiveIndex.value - 1, 0); return }
  if (event.key === 'Enter' && cityOpen.value && filteredCities.value[cityActiveIndex.value]) { event.preventDefault(); selectCity(filteredCities.value[cityActiveIndex.value]!) }
}
function documentPointerDown(event: PointerEvent) { if (cityOpen.value && cityField.value && !cityField.value.contains(event.target as Node)) closeCityPicker() }
function focusDetail() { focusKeys[tab.value] = selectedRow.value?.representative.key || null; document.getElementById('job-detail-title')?.focus() }
function restoreRouteSelection() {
  const requested = typeof route.query.job === 'string' ? route.query.job : null
  if (requested && rows.value.some(row => row.sources.some(job => job.key === requested))) selection.value[tab.value] = requested
}
function beforeUnload(event: BeforeUnloadEvent) {
  if (jdOpen.value && jdContext.value && (jdName.value !== jdContext.value.initialName || jdContent.value !== jdContext.value.initialContent)) { event.preventDefault(); event.returnValue = '' }
}
function pageHide() {
  if (left || !task.value) return
  epoch++; searchIdentity++; stopPolling(); detailStates.value = {}
  task.value = { ...task.value, status: 'STOPPED', sources: task.value.sources.map(source => ({ ...source, status: source.status === 'SEARCHING' ? 'STOPPED' : source.status })) }
  void jobSearch.stop(task.value.id, true).catch(() => {})
}
function leave() { if (left) return; left = true; epoch++; lifecycle++; stopPolling(); if (task.value) void jobSearch.stop(task.value.id).catch(() => {}) }
onBeforeRouteLeave(() => { if (jdBusy.value) return false; if (jdOpen.value) { closeJd(false); if (jdOpen.value) return false } leave(); return true })
watch(linkKeys, keys => { const missing = keys.filter(key => !linkStates.value[key]); void lookup(missing) })
watch(allJobs, jobs => { experienceOptions.value = [...new Set([...experienceOptions.value, ...originalOptions(jobs, 'experienceText')])]; educationOptions.value = [...new Set([...educationOptions.value, ...originalOptions(jobs, 'educationText')])] }, { flush: 'sync' })
watch(() => selectedJob.value?.key, () => { if (selectedJob.value && tab.value === 'search') void ensureDetail(selectedJob.value) })
watch(searchRows, current => { if (selection.value.search && !current.some(row => row.sources.some(job => job.key === selection.value.search))) { selection.value.search = null; if (tab.value === 'search' && route.query.job) void backToList() } })
watch(() => route.query, async (query, previous) => { tab.value = query.tab === 'favorites' ? 'favorites' : 'search'; restoreRouteSelection(); if (previous.job && !query.job) await restoreScroll(tab.value, true) })
watch(mobileDetail, showing => { if (!showing) nextTick(measure) })
onMounted(() => { measure(); if (typeof ResizeObserver !== 'undefined') { observer = new ResizeObserver(measure); if (page.value) observer.observe(page.value); if (workspace.value) observer.observe(workspace.value) } window.addEventListener('resize', measure); window.addEventListener('beforeunload', beforeUnload); window.addEventListener('pagehide', pageHide); document.addEventListener('pointerdown', documentPointerDown); void loadCities(); void loadFavorites() })
onBeforeUnmount(() => { leave(); observer?.disconnect(); window.removeEventListener('resize', measure); window.removeEventListener('beforeunload', beforeUnload); window.removeEventListener('pagehide', pageHide); document.removeEventListener('pointerdown', documentPointerDown) })
</script>

<template>
  <section ref="page" class="page job-search-page" :class="{ 'single-detail': mobileDetail }" aria-label="岗位搜索工作区">
    <template v-if="!mobileDetail">
      <header class="page-heading"><h1>岗位搜索</h1><p>搜索目标岗位，按来源比较要求，收藏后保存为目标 JD。</p></header>
      <div class="tabs" role="tablist" aria-label="岗位视图" @keydown="tabKey">
        <button v-for="item in ([{ id: 'search', label: '搜索职位' }, { id: 'favorites', label: '我的收藏' }] as const)" :id="`jobs-tab-${item.id}`" :key="item.id" class="tab" :class="{ active: tab === item.id }" role="tab" :aria-selected="tab === item.id" :tabindex="tab === item.id ? 0 : -1" @click="setTab(item.id)">{{ item.label }}</button>
      </div>
      <Notice :message="notice" />
      <template v-if="tab === 'search'">
        <form id="job-search-form" class="job-search-form" @submit.prevent="search">
          <div class="job-search-fields"><div class="form-field"><label for="job-keyword">岗位关键词</label><input id="job-keyword" v-model="keyword" placeholder="例如：Java 后端" /></div><div ref="cityField" class="form-field city-field"><label for="job-city">城市</label><div class="city-combobox"><input id="job-city" v-model="cityInput" role="combobox" aria-autocomplete="list" aria-haspopup="listbox" :aria-expanded="cityOpen" aria-controls="job-city-options" :aria-activedescendant="cityOpen && filteredCities[cityActiveIndex] ? `job-city-option-${filteredCities[cityActiveIndex].id}` : undefined" autocomplete="off" :disabled="citiesLoading || !!citiesError" placeholder="输入或选择城市" @focus="openCityPicker" @input="cityInputChanged" @keydown="cityKeydown" /><button v-if="cityInput" type="button" class="city-clear" aria-label="清除城市" @click="clearCity"><X :size="15" /></button><button type="button" class="city-toggle" :aria-label="cityOpen ? '收起城市列表' : '展开城市列表'" :aria-expanded="cityOpen" :disabled="citiesLoading || !!citiesError" @click="cityOpen ? closeCityPicker() : openCityPicker()"><ChevronDown :size="16" aria-hidden="true" /></button></div><div v-if="cityOpen" id="job-city-options" class="city-menu" role="listbox" aria-label="支持的城市"><div class="city-menu-head"><span>选择工作城市</span><span>{{ filteredCities.length }} 个可选</span></div><div v-if="!cityInput" class="city-quick"><span>热门城市</span><div class="city-quick-list"><button v-for="item in cityQuickPicks" :key="item.id" type="button" class="city-chip" :class="{ selected: item.name === cityInput }" @click="selectCity(item)">{{ item.name }}<Check v-if="item.name === cityInput" :size="13" aria-hidden="true" /></button></div></div><div v-if="filteredCities.length" class="city-options"><button v-for="(item, index) in filteredCities" :id="`job-city-option-${item.id}`" :key="item.id" type="button" class="city-option" :class="{ active: index === cityActiveIndex, selected: item.name === cityInput }" role="option" :aria-selected="item.name === cityInput" @mouseenter="cityActiveIndex = index" @click="selectCity(item)"><span>{{ item.name }}</span><Check v-if="item.name === cityInput" :size="16" aria-hidden="true" /></button></div><p v-else class="city-empty">没有匹配的城市，请换一个关键词。</p><p class="city-menu-foot">仅支持列表中的城市，共 {{ cities.length }} 个。</p></div></div><button class="btn btn-primary search-submit" :disabled="creating || citiesLoading || !!citiesError"><Search :size="16" />{{ creating ? '正在发起…' : running ? '搜索新条件' : '开始搜索' }}</button></div>
          <fieldset class="source-checks"><legend>搜索平台</legend><label v-for="source in jobSources" :key="source.value"><input :id="`job-search-platform-${source.value}`" v-model="selectedSources" type="checkbox" :value="source.value" />{{ source.label }}</label></fieldset>
          <Notice :message="validation" kind="error" /><LoadingState v-if="citiesLoading" label="正在读取支持的城市…" /><div v-if="citiesError"><Notice :message="citiesError" kind="error" /><button class="text-link" @click.prevent="loadCities">重新读取城市</button></div>
        </form>
        <p v-if="task" class="search-runbar"><strong>本次：{{ task.keyword }}／{{ task.cityName }}</strong><span v-if="changed">条件已修改，点击搜索后生效</span><button v-if="running || taskError.startsWith('停止请求未确认')" class="btn btn-secondary" :disabled="actionBusy" @click="stopSearch">停止本次搜索</button><span v-if="task.status === 'STOPPED'">已停止，保留已取得结果</span></p>
        <div v-if="task" class="source-progress" aria-live="polite" aria-label="来源进度"><div v-for="progress in task.sources" :key="progress.source"><strong>{{ sourceLabel(progress.source) }}</strong><span>{{ progressText(progress) }}</span><span v-if="progress.errorMessage" class="source-error">{{ progress.errorMessage }}</span><button v-if="progress.status === 'FAILED' && task.status !== 'STOPPED'" class="text-link" :disabled="actionBusy" @click="taskAction('retry', progress.source)">重试{{ sourceLabel(progress.source) }}</button></div></div>
        <Notice :message="taskError" kind="error" /><button v-if="taskError && running" class="text-link" @click="taskError = ''; schedulePoll()">重试读取进度</button>
        <section v-if="task && allJobs.length" class="job-filters-section" aria-label="筛选已获取结果"><div class="filter-heading"><strong>筛选已获取结果</strong><button class="text-link mobile-filter-toggle" :aria-expanded="filtersOpen" @click="filtersOpen = !filtersOpen">筛选（{{ filterCount }}）</button><span class="muted tiny">仅作用于本次已获取结果</span><button class="text-link" @click="clearFilters">清除筛选</button></div>
          <div class="job-filters" :class="{ expanded: filtersOpen }">
            <div class="filter-field"><label for="job-source-filter">结果来源</label><select id="job-source-filter" v-model="filters.source"><option value="">全部已搜索来源</option><option v-for="source in task.sources" :key="source.source" :value="source.source">{{ sourceLabel(source.source) }}</option></select></div>
            <div class="filter-field salary-filter"><label for="job-salary-filter">月薪（k／月）</label><details id="job-salary-filter"><summary>{{ filters.minK ?? '不限' }}～{{ filters.maxK ?? '不限' }}</summary><div class="salary-inputs"><label>下限<input v-model="salaryMin" aria-label="最低月薪" inputmode="decimal" @blur="commitSalary" @keydown.enter.prevent="commitSalary" /></label><label>上限<input v-model="salaryMax" aria-label="最高月薪" inputmode="decimal" @blur="commitSalary" @keydown.enter.prevent="commitSalary" /></label></div></details><label class="check-label"><input v-model="filters.unknownSalary" type="checkbox" />保留未知／面议</label></div>
            <div class="filter-field"><label for="job-experience">经验要求</label><select id="job-experience" v-model="filters.experience"><option value="">全部</option><option v-for="value in experienceOptions" :key="value">{{ value }}</option></select><label class="check-label"><input v-model="filters.unknownExperience" type="checkbox" />保留未知经验</label></div>
            <div class="filter-field"><label for="job-education">学历要求</label><select id="job-education" v-model="filters.education"><option value="">全部</option><option v-for="value in educationOptions" :key="value">{{ value }}</option></select><label class="check-label"><input v-model="filters.unknownEducation" type="checkbox" />保留未知学历</label></div>
          </div><Notice :message="salaryError" kind="error" /><p v-if="filtersOpen" class="tiny muted">未知表示未获取或无法可靠解释；原文明确“不限”是已知要求。</p><p v-if="filterCount" class="filter-summary">已设 {{ filterCount }} 项条件：{{ filters.source ? sourceLabel(filters.source) : '全部来源' }}；月薪 {{ filters.minK ?? '不限' }}～{{ filters.maxK ?? '不限' }}k；经验 {{ filters.experience || '全部' }}；学历 {{ filters.education || '全部' }}</p>
        </section>
      </template>
      <template v-else><p class="favorites-intro">保存的是收藏时已获取的内容，不自动同步原站。</p><Notice v-if="running" message="搜索仍在进行，切回搜索职位即可查看进度。"><button class="text-link" :disabled="actionBusy" @click="stopSearch">停止本次搜索</button></Notice><LoadingState v-if="favoritesLoading" label="正在读取本机收藏…" /><Notice :message="favoritesError" kind="error" /><button v-if="favoritesError" class="text-link" @click="loadFavorites">重新读取收藏</button></template>
    </template>
    <Notice v-if="tab === 'search' && favoritesError" message="收藏状态暂不可用，请重新读取后操作。" kind="warning"><button class="text-link" @click="loadFavorites">重新读取收藏</button></Notice>
    <div ref="workspace" class="job-workspace" :class="{ dual: dual && rows.length, 'show-detail': mobileDetail }" :style="dual && rows.length ? { height: `${workspaceHeight}px` } : undefined">
      <section v-show="!mobileDetail" ref="list" class="job-list-pane" aria-label="岗位列表">
        <p v-if="rows.length || (tab === 'search' && allJobs.length)" class="results-count">显示 {{ rows.length }} 个岗位<span v-if="tab === 'search'">／已获取 {{ allJobs.length }} 条来源岗位</span></p>
        <button v-if="dual && selectedJob" class="text-link detail-jump" @click="focusDetail">转到岗位详情</button>
        <LoadingState v-if="tab === 'search' && (creating || (running && !allJobs.length))" label="正在逐站搜索，已返回的岗位将陆续显示…" />
        <EmptyState v-else-if="tab === 'search' && !task && !taskError" title="输入岗位关键词和城市，搜索所选平台"><button class="btn btn-secondary" @click="focusKeyword">开始设置</button></EmptyState>
        <EmptyState v-else-if="tab === 'search' && task && !allJobs.length" :title="task.sources.every(source => source.status === 'FAILED') ? '本次搜索未能取得结果' : task.status === 'STOPPED' ? '已停止，尚未取得岗位' : task.sources.some(source => source.status === 'FAILED') ? '部分来源未完成，本次范围不完整' : '所选平台本次未找到岗位'" description="可修改条件重新搜索；失败来源可在上方单独重试。" />
        <EmptyState v-else-if="tab === 'search' && allJobs.length && !rows.length" title="当前筛选无匹配"><button class="btn btn-secondary" @click="clearFilters">清除筛选</button></EmptyState>
        <EmptyState v-else-if="tab === 'favorites' && !favoritesLoading && !favoritesError && !rows.length" title="还没有收藏的岗位"><button id="jobs-go-search" class="btn btn-secondary" @click="setTab('search')">去搜索</button></EmptyState>
        <div v-if="rows.length" class="job-list-header" role="row" aria-label="岗位列表字段"><span>更新时间</span><span>公司与职位</span><span>城市</span><span>薪资与要求</span><span>来源</span><span>操作</span></div>
        <article v-for="row in rows" :key="row.id" class="job-row" :class="{ selected: row.sources.some(job => job.key === selectedKey) }" role="listitem">
          <button class="job-row-main" :data-job-select="row.representative.key" @click="choose(row)"><span class="job-cell job-cell-time"><strong>{{ dateTime(row.representative.fetchedAt) }}</strong><small v-if="row.representative.publishedAt">发布 {{ row.representative.publishedAt }}</small></span><span class="job-cell job-cell-title"><strong>{{ row.representative.title || '岗位名称未获取' }}</strong><small>{{ row.representative.company || '公司未知' }}</small><small v-if="row.favorite">首次收藏 {{ dateTime(row.favorite.createdAt) }}</small></span><span class="job-cell job-cell-location"><span class="job-tag">{{ row.representative.city || '城市未知' }}</span><small>{{ row.representative.location || '地点未知' }}</small></span><span class="job-cell job-cell-requirements"><strong>{{ row.representative.salaryText || '薪资未知' }}</strong><small>{{ row.representative.experienceText || '经验未知' }} · {{ row.representative.educationText || '学历未知' }}</small><small v-if="row.representative.salaryMonths || (row.representative.salaryMinK == null && row.representative.salaryMaxK == null)">{{ row.representative.salaryMonths ? `${row.representative.salaryMonths} 薪` : '月薪口径未知' }}</small></span><span class="job-cell job-cell-source"><span class="job-tag source-tag">{{ sourceLabel(row.representative.source) }}</span><small>{{ sourceLabel(row.representative.source) }}概要 · 共 {{ row.sources.length }} 个来源</small><small v-if="sourceDifference(row.sources)" class="source-difference">信息有差异</small></span></button>
          <div class="job-row-actions"><button v-if="!row.favorite" data-job-favorite class="btn btn-ghost" :disabled="favoriteBusy[row.id] || !favoritesReady" :aria-label="`${favoriteLabel(row)}：${row.representative.title}`" @click="favorite(row)">{{ favoriteLabel(row) }}</button><button v-if="row.favorite || favoriteFor(row)" data-cancel-favorite class="text-link" :aria-label="`取消收藏：${row.representative.title}`" @click="askCancel(row.favorite || favoriteFor(row)!)">取消收藏</button></div><Notice :message="favoriteErrors[row.id]" kind="error" />
        </article>
        <footer v-if="tab === 'search' && task && allJobs.length" class="results-footer"><button v-if="canMore" class="btn btn-secondary" :disabled="running || actionBusy" @click="taskAction('more')">{{ actionBusy ? '正在加载…' : '加载更多' }}</button><p class="tiny muted">{{ task.status === 'STOPPED' ? '已停止；继续查询需重新搜索。' : task.sources.some(source => source.status === 'LIMIT') ? '部分来源已达本次获取上限，每站最多 100 条。' : !running && !canMore ? '已获取本次可用结果。' : '每批每站最多 20 条，按需加载更多。' }}</p></footer>
      </section>
      <section v-if="selectedJob && (dual || mobileDetail)" ref="detail" data-job-detail class="job-detail" aria-labelledby="job-detail-title">
        <button v-if="!dual" class="btn btn-ghost back-list" @click="backToList">返回列表</button>
        <h2 id="job-detail-title" tabindex="-1">{{ selectedJob.title }}</h2><p class="detail-company">{{ selectedJob.company || '公司未知' }}</p>
        <div class="detail-sticky"><div class="detail-sources" role="group" aria-label="岗位来源"><button v-for="job in selectedRow!.sources" :key="job.key" class="btn btn-secondary" :aria-pressed="job.key === selectedKey" @click="chooseSource(job.key)">{{ sourceLabel(job.source) }}<span v-if="tab === 'search' && !matchesJob(job, filters)">（未匹配当前筛选）</span></button></div><div class="detail-actions"><button v-if="tab === 'search'" class="btn btn-secondary" :disabled="favoriteBusy[selectedRow!.id] || !favoritesReady" @click="favorite(selectedRow!)">{{ favoriteLabel(selectedRow!) }}</button><button v-else class="btn btn-secondary" @click="askCancel(selectedRow!.favorite!)">取消收藏</button><button v-if="currentLinkState === 'ready'" class="btn btn-primary" @click="jdAction">{{ links[selectedJob.key] ? '查看已保存 JD' : '保存 JD' }}</button><span v-else-if="currentLinkState === 'loading'" role="status">正在读取保存状态…</span><span v-else class="source-error">保存状态暂不可用 <button class="text-link" @click="lookup([selectedJob.key])">重试保存状态</button></span></div></div>
        <Notice :message="favoriteErrors[selectedRow!.id]" kind="error" />
        <p v-if="tab === 'search' && !matchesJob(selectedJob, filters)" class="source-difference">当前来源未匹配当前筛选，保留阅读。</p>
        <details v-if="sourceDifference(selectedRow!.sources)" class="source-comparison"><summary>来源信息有差异 · 查看来源差异</summary><p v-for="job in selectedRow!.sources" :key="job.key">{{ sourceLabel(job.source) }}：{{ job.salaryText || '薪资未知' }}；{{ job.experienceText || '经验未知' }}；{{ job.educationText || '学历未知' }}</p></details>
        <dl class="detail-facts"><div><dt>薪资原文</dt><dd>{{ selectedJob.salaryText || '薪资未知' }}<span v-if="selectedJob.salaryMonths"> · {{ selectedJob.salaryMonths }} 薪</span></dd></div><div><dt>地点</dt><dd>{{ selectedJob.location || selectedJob.city || '地点未知' }}</dd></div><div><dt>经验要求</dt><dd>{{ selectedJob.experienceText || '经验未知' }}</dd></div><div><dt>学历要求</dt><dd>{{ selectedJob.educationText || '学历未知' }}</dd></div></dl>
        <div class="detail-times"><p>来源：{{ sourceLabel(selectedJob.source) }} · 获取 {{ dateTime(selectedJob.fetchedAt) }}</p><p v-if="selectedJob.detailFetchedAt">正文获取 {{ dateTime(selectedJob.detailFetchedAt) }}</p><p v-if="selectedJob.publishedAt">发布时间 {{ selectedJob.publishedAt }}</p><p v-if="selectedJob.savedAt">此来源收藏于 {{ dateTime(selectedJob.savedAt) }}</p><a v-if="selectedJob.url" class="text-link" :href="selectedJob.url" target="_blank" rel="noopener noreferrer">查看原岗位（新标签打开）</a><span v-else>原链接未获取</span></div>
        <LoadingState v-if="tab === 'search' && currentDetailState?.loading" label="正在读取当前来源正文…" /><template v-else-if="tab === 'search' && currentDetailState?.error"><Notice :message="currentDetailState.error" kind="error" /><button class="btn btn-secondary" @click="ensureDetail(selectedJob, true)">重试正文</button></template>
        <pre v-if="selectedJob.description" class="job-description">{{ selectedJob.description }}</pre><p v-else-if="!currentDetailState?.loading" class="detail-missing">{{ tab === 'favorites' ? '收藏时未获取正文' : '未获取岗位正文' }}，可查看原岗位，或在保存 JD 时手工补充。</p>
        <button v-if="tab === 'search' && !selectedJob.description && !currentDetailState?.loading && !currentDetailState?.error" class="btn btn-secondary" @click="ensureDetail(selectedJob, true)">重新读取正文</button>
      </section>
      <EmptyState v-else-if="dual && rows.length" title="选择一个岗位查看详情" />
      <EmptyState v-else-if="mobileDetail && !selectedJob && !favoritesLoading" title="本次岗位上下文已结束" description="重新搜索后可查看岗位；收藏可从“我的收藏”重新打开。"><button class="btn btn-secondary" @click="backToList">返回列表</button></EmptyState>
    </div>
    <Modal :open="!!cancelTarget" title="取消收藏" description="取消后无法恢复收藏时的快照。" :busy="cancelBusy" @update:open="value => { if (!value && !cancelBusy) cancelTarget = null }"><p>将移除这条收藏及其中 {{ cancelTarget?.sources.length }} 个来源快照，已保存 JD 保留。</p><Notice :message="cancelError" kind="error" /><div class="dialog-actions"><button class="btn btn-secondary" :disabled="cancelBusy" @click="cancelTarget = null">保留收藏</button><button class="btn btn-danger" :disabled="cancelBusy" @click="removeFavorite">{{ cancelBusy ? '正在取消…' : '确认取消收藏' }}</button></div></Modal>
    <Modal :open="jdOpen" variant="drawer" title="保存目标 JD" description="预览当前来源；修改正文后仍保留原来源信息。" :busy="jdBusy" @update:open="closeJd"><form id="job-jd-form" class="job-jd-form" @submit.prevent="saveJd"><p v-if="jdContext" class="jd-origin">{{ sourceLabel(jdContext.job.source) }} · {{ jdContext.job.title }} · 获取 {{ dateTime(jdContext.job.fetchedAt) }}</p><div class="form-field"><label for="job-jd-name">JD 名称（必填）</label><input id="job-jd-name" v-model="jdName" :disabled="jdBusy" aria-describedby="jd-name-count" /><p id="jd-name-count" class="form-help">{{ jdName.length }} / 120 字符<span v-if="jdName.length > 120" class="source-error">，超出 {{ jdName.length - 120 }} 个字符</span></p></div><div class="form-field"><label for="job-jd-content">岗位正文（必填）</label><textarea id="job-jd-content" v-model="jdContent" :disabled="jdBusy" rows="18" aria-describedby="jd-content-count" /><p id="jd-content-count" class="form-help">{{ jdContent.length }} / 24,000 字符<span v-if="jdContent.length > 24000" class="source-error">，超出 {{ jdContent.length - 24000 }} 个字符</span></p><p v-if="!jdContext?.initialContent" class="form-help">未获取岗位正文，请补充后保存。</p></div><Notice :message="jdError" kind="error" /></form><template #footer><div class="dialog-actions"><button class="btn btn-secondary" :disabled="jdBusy" @click="closeJd(false)">取消</button><button class="btn btn-primary" form="job-jd-form" type="submit" :disabled="jdBusy">{{ jdBusy ? '正在保存…' : '保存到资料库' }}</button></div></template></Modal>
  </section>
</template>

<style scoped>
.job-search-page { font-size:14px; }.job-search-page h1 { font-size:24px; font-weight:650; line-height:1.4; }.job-search-page button,.job-search-page input,.job-search-page select { font-size:14px; }.job-search-page .btn { min-height:36px; }.job-search-page :is(button,a,input,select,summary):focus-visible { outline:2px solid var(--accent); outline-offset:2px; }.job-search-fields { display:grid; grid-template-columns:minmax(0,1fr) minmax(220px,260px) auto; gap:12px; align-items:end; }.job-search-fields .form-field { margin:0; }.city-field { position:relative; z-index:5; }.city-combobox { position:relative; }.city-combobox input { padding-right:68px; }.city-toggle,.city-clear { position:absolute; top:50%; display:grid; place-items:center; width:32px; height:32px; padding:0; border:0; background:transparent; color:var(--text-muted); transform:translateY(-50%); }.city-toggle { right:4px; }.city-clear { right:34px; }.city-toggle:hover,.city-clear:hover { color:var(--text-primary); background:var(--surface-soft); border-radius:4px; }.city-toggle:disabled { cursor:not-allowed; opacity:.55; }.city-menu { position:absolute; top:calc(100% + 8px); right:0; left:0; z-index:20; overflow:hidden; border:1px solid var(--border-control); border-radius:8px; background:var(--surface); box-shadow:0 14px 32px rgba(15,23,42,.14); }.city-menu-head { display:flex; justify-content:space-between; gap:12px; padding:12px 14px; border-bottom:1px solid var(--border-subtle); color:var(--text-secondary); font-size:12px; }.city-menu-head span:last-child { color:var(--text-muted); }.city-quick { padding:12px 14px 10px; border-bottom:1px solid var(--border-subtle); }.city-quick > span { display:block; margin-bottom:8px; color:var(--text-muted); font-size:12px; }.city-quick-list { display:flex; flex-wrap:wrap; gap:6px; }.city-chip { display:inline-flex; align-items:center; gap:4px; min-height:30px; padding:4px 9px; border:1px solid var(--border-subtle); border-radius:999px; background:var(--surface-soft); color:var(--text-secondary); font-size:13px; }.city-chip:hover,.city-chip.selected { border-color:#93b4ff; background:var(--accent-soft); color:var(--accent-ink); }.city-options { max-height:210px; overflow-y:auto; padding:6px; }.city-option { display:flex; align-items:center; justify-content:space-between; gap:12px; width:100%; min-height:38px; padding:8px 9px; border:0; border-radius:5px; background:transparent; color:var(--text-primary); text-align:left; }.city-option:hover,.city-option.active { background:var(--accent-soft); color:var(--accent-ink); }.city-option.selected { font-weight:600; }.city-empty { padding:18px 14px; color:var(--text-muted); font-size:13px; }.city-menu-foot { padding:9px 14px 11px; border-top:1px solid var(--border-subtle); color:var(--text-muted); font-size:12px; }.source-checks { display:flex; flex-wrap:wrap; gap:8px 24px; align-items:center; margin:12px 0 16px; border:0; padding:0; }.source-checks legend { float:left; margin-right:20px; color:var(--text-secondary); }.source-checks label,.check-label { display:inline-flex; align-items:center; flex-shrink:0; gap:8px; width:auto; min-height:32px; margin:0; white-space:nowrap; font-size:14px; font-weight:400; }.source-checks input[type=checkbox],.check-label input[type=checkbox] { flex:0 0 18px; width:18px; height:18px; margin:0; padding:0; accent-color:var(--accent); }.search-runbar { display:flex; flex-wrap:wrap; align-items:center; gap:12px; margin:16px 0 8px; }.search-runbar strong { font-weight:500; }.search-runbar > span { color:var(--text-muted); }.source-progress { display:flex; flex-wrap:wrap; gap:8px 24px; padding:12px 0 16px; border-bottom:1px solid var(--border-subtle); }.source-progress > div { display:flex; flex-wrap:wrap; gap:8px; align-items:center; }.source-progress strong { font-weight:500; }.source-progress span { color:var(--text-muted); }.source-error,.source-progress .source-error { color:var(--danger,#be123c); }.job-filters-section { padding:16px 0; border-bottom:1px solid var(--border-subtle); }.filter-heading { display:flex; flex-wrap:wrap; align-items:center; gap:12px; margin-bottom:12px; }.filter-heading strong { font-weight:500; }.filter-heading > button:last-child { margin-left:auto; }.job-filters { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:16px; }.filter-field { min-width:0; }.filter-field > label:first-child { display:block; margin-bottom:6px; }.filter-field .check-label { margin-top:8px; font-size:12px; }.salary-filter summary { cursor:pointer; min-height:36px; padding:8px; border:1px solid var(--border-control); border-radius:var(--radius-control); }.salary-inputs { display:flex; gap:8px; margin:8px 0; }.salary-inputs label { min-width:0; }.filter-summary { margin-top:10px; font-size:12px; color:var(--text-muted); overflow-wrap:anywhere; }.mobile-filter-toggle { display:none; }.favorites-intro { color:var(--text-muted); margin-bottom:16px; }.job-workspace { min-width:0; margin-top:16px; }.job-workspace.dual { display:grid; grid-template-columns:minmax(360px,min(40%,480px)) minmax(0,1fr); border:1px solid var(--border-subtle); border-radius:var(--radius-panel); overflow:hidden; }.job-list-pane { min-width:0; }.dual .job-list-pane,.dual .job-detail { overflow-y:auto; overscroll-behavior:contain; scrollbar-gutter:stable; }.results-count { font-size:12px; color:var(--text-muted); padding:12px 16px; border-bottom:1px solid var(--border-subtle); margin:0; }.job-row { border-bottom:1px solid var(--border-subtle); min-width:0; border-left:2px solid transparent; }.job-row.selected { background:var(--accent-soft); border-left-color:var(--accent); }.job-row:hover { background:var(--surface-soft); }.job-row.selected:hover { background:var(--accent-soft); }.job-row-main { display:block; padding:16px 16px 4px 14px; width:100%; text-align:left; border:0; background:transparent; color:inherit; min-width:0; }.job-row-heading { display:flex; flex-wrap:wrap; gap:8px 16px; justify-content:space-between; }.job-row h2 { font-size:16px; font-weight:600; line-height:1.5; margin:0; display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; overflow-wrap:anywhere; }.job-salary { font-size:16px; color:var(--text-primary); overflow-wrap:anywhere; }.job-company { margin:8px 0; display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; }.job-requirements { color:var(--text-secondary); font-size:14px; margin:0 0 8px; overflow-wrap:anywhere; }.job-row-meta { font-size:12px; color:var(--text-muted); line-height:1.75; margin:0; overflow-wrap:anywhere; }.source-difference { color:var(--warning,#92400e); font-size:12px; margin:8px 0; }.job-row-actions { display:flex; flex-wrap:wrap; gap:8px; justify-content:flex-end; padding:0 16px 10px; }.job-row :deep(.notice) { margin:0 16px 12px; }.results-footer { padding:16px; text-align:center; }.results-footer p { margin-top:8px; }.job-detail { min-width:0; padding:24px; overflow-wrap:anywhere; }.dual .job-detail { border-left:1px solid var(--border-subtle); }.job-detail h2 { font-size:20px; line-height:1.5; font-weight:600; margin:0 0 8px; }.detail-company { font-size:14px; color:var(--text-secondary); margin-bottom:16px; }.detail-sticky { position:sticky; top:-24px; background:var(--surface); z-index:1; padding:12px 0; border-block:1px solid var(--border-subtle); }.detail-sources,.detail-actions { display:flex; flex-wrap:wrap; gap:8px; align-items:center; }.detail-sources { margin-bottom:12px; }.detail-sources .btn { white-space:normal; text-align:left; }.detail-sources [aria-pressed=true] { color:var(--accent); background:var(--accent-soft); border-color:var(--accent); }.detail-actions { font-size:12px; }.detail-facts { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:16px; margin:20px 0; }.detail-facts dt { font-size:12px; color:var(--text-muted); margin-bottom:4px; }.detail-facts dd { margin:0; font-size:14px; }.detail-times { font-size:12px; line-height:1.75; color:var(--text-muted); margin:16px 0 24px; }.detail-times p { margin:0 0 4px; }.source-comparison { margin:16px 0; font-size:14px; }.source-comparison summary { cursor:pointer; color:var(--warning,#92400e); }.job-description { white-space:pre-wrap; overflow-wrap:anywhere; word-break:normal; font-family:inherit; font-size:14px; line-height:1.75; max-width:72ch; color:var(--text-secondary); }.detail-missing { font-size:14px; line-height:1.75; color:var(--text-muted); }.jd-origin { margin-bottom:20px; font-size:12px; color:var(--text-muted); overflow-wrap:anywhere; }.job-jd-form input,.job-jd-form textarea { font-size:14px; }.job-jd-form textarea { line-height:1.75; }.back-list { margin-bottom:16px; }.job-workspace:not(.dual) .job-list-pane { border-block:1px solid var(--border-subtle); }.job-workspace:not(.dual) .job-row { min-height:152px; }.single-detail { padding-top:12px; }.single-detail .job-workspace { margin-top:0; }.single-detail .job-detail { padding:0 0 100px; }.single-detail .detail-sticky { position:static; }.single-detail .detail-actions { position:fixed; inset:auto 0 0; z-index:10; padding:12px 16px max(12px,env(safe-area-inset-bottom)); background:var(--surface); border-top:1px solid var(--border-subtle); box-shadow:0 -2px 12px #0f172a0a; }.single-detail .detail-actions .btn { min-height:44px; }
@media(max-width:1100px) { .job-search-page .btn,.job-search-page input:not([type=checkbox]),.job-search-page select { min-height:44px; }.job-filters { grid-template-columns:repeat(2,minmax(0,1fr)); } }
@media(max-width:600px) { .job-search-fields { grid-template-columns:minmax(0,1fr) auto; }.job-search-fields .form-field:first-child { grid-column:1 / -1; }.city-field { min-width:0; }.job-search-fields input,.job-jd-form input,.job-jd-form textarea { font-size:16px; }.job-search-page h1 { font-size:22px; }.source-checks { gap:12px 16px; }.source-checks legend { float:none; margin:0 0 8px; }.mobile-filter-toggle { display:inline-flex; }.job-filters { display:none; grid-template-columns:1fr; }.job-filters.expanded { display:grid; }.filter-heading > span { flex-basis:100%; }.job-row-heading { display:block; }.job-salary { display:block; margin-top:4px; }.job-detail { padding-inline:0; }.detail-facts { gap:12px; } }
@media(prefers-reduced-motion:reduce) { .job-search-page * { scroll-behavior:auto!important; transition:none!important; } }

/* 结果筛选统一控件高度，列表采用对齐的字段行。 */
.job-filters { align-items: start; }
.filter-field > label:first-child { min-height: 22px; margin-bottom: 8px; }
.filter-field > select, .salary-filter details > summary { min-height: 44px; height: 44px; display: flex; align-items: center; }
.filter-field .check-label { min-height: 44px; margin-top: 8px; align-items: center; }
.salary-filter details { margin: 0; }
.salary-filter details > summary { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.job-list-header { display: grid; grid-template-columns: 86px minmax(0,1.5fr) minmax(0,.85fr) minmax(0,1.15fr) minmax(0,.85fr) 110px; gap: 12px; align-items: center; padding: 12px 14px; border-block: 1px solid var(--border-subtle); background: var(--surface-soft); color: var(--text-secondary); font-size: 12px; font-weight: 600; }
.job-list-header span:last-child { text-align: right; }
.job-row { display: grid; grid-template-columns: minmax(0,1fr) 110px; align-items: stretch; }
.job-row-main { display: grid; grid-template-columns: 86px minmax(0,1.5fr) minmax(0,.85fr) minmax(0,1.15fr) minmax(0,.85fr); gap: 12px; align-items: center; padding: 14px; }
.job-cell { display: flex; min-width: 0; flex-direction: column; gap: 3px; overflow-wrap: anywhere; }
.job-cell strong { font-size: 14px; font-weight: 600; line-height: 1.45; }
.job-cell small { color: var(--text-muted); font-size: 12px; line-height: 1.55; }
.job-cell-title strong { color: var(--text-primary); }
.job-cell-requirements strong { color: var(--accent-ink); }
.job-cell-location, .job-cell-source { align-items: flex-start; }
.job-tag { display: inline-flex; align-items: center; width: fit-content; max-width: 100%; min-height: 28px; padding: 4px 9px; border-radius: 5px; background: var(--surface-soft); color: var(--text-secondary); font-size: 13px; line-height: 1.4; }
.source-tag { background: var(--accent-soft); color: var(--accent-ink); }
.job-row-actions { min-width: 0; align-items: center; justify-content: center; padding: 12px 10px; border-left: 1px solid var(--border-subtle); }
.job-row-actions .btn, .job-row-actions .text-link { white-space: normal; text-align: center; }
.job-row:hover .job-row-actions { background: var(--surface-soft); }
.job-row.selected .job-row-actions { background: var(--accent-soft); }
.job-row :deep(.notice) { grid-column: 1 / -1; }

@media(max-width:900px) {
  .job-list-header { grid-template-columns: 76px minmax(130px,1.4fr) minmax(78px,.8fr) minmax(110px,1fr) minmax(78px,.75fr) 92px; gap: 8px; padding-inline: 10px; }
  .job-row { grid-template-columns: minmax(0,1fr) 92px; }
  .job-row-main { grid-template-columns: 76px minmax(130px,1.4fr) minmax(78px,.8fr) minmax(110px,1fr) minmax(78px,.75fr); gap: 8px; padding-inline: 10px; }
}

@media(max-width:600px) {
  .job-list-header { display: none; }
  .job-row, .job-row-main { display: block; }
  .job-row-main { padding: 14px 14px 8px; }
  .job-cell { display: block; margin-bottom: 6px; }
  .job-cell strong, .job-cell small { display: inline; }
  .job-cell small::before { content: ' · '; color: var(--border-control); }
  .job-cell-time small::before, .job-cell-title small:first-of-type::before { content: ''; }
  .job-cell-location .job-tag, .job-cell-source .job-tag { display: inline-flex; margin-right: 6px; }
  .job-row-actions { justify-content: flex-end; padding: 0 14px 12px; border-left: 0; }
}
</style>
