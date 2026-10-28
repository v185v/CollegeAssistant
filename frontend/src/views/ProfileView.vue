<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { ApiClientError, isAuthenticationStatus } from '../api/client'
import { getProfile, updateProfile } from '../api/endpoints'
import type {
  CourseRecordInput,
  Degree,
  LifecycleStage,
  UpdateProfileRequest,
  UserProfileDto,
} from '../api/types'

interface CourseRecordForm {
  key: number
  courseCode: string
  courseName: string
  term: string
}

const MAX_COURSE_RECORDS = 100

const emit = defineEmits<{
  unauthorized: []
}>()

const major = ref('')
const grade = ref('')
const campus = ref('')
const degree = ref<Degree | ''>('')
const college = ref('')
const lifecycleStage = ref<LifecycleStage | ''>('')
const courseRecords = ref<CourseRecordForm[]>([])
const updatedAt = ref<string | null>(null)
const loading = ref(false)
const profileReady = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
let courseKey = 0
const courseLimitReached = computed(
  () => courseRecords.value.length >= MAX_COURSE_RECORDS,
)

const degreeOptions: Array<{ value: Degree; label: string }> = [
  { value: 'UNDERGRADUATE', label: '本科' },
  { value: 'POSTGRADUATE', label: '硕士研究生' },
  { value: 'DOCTORAL', label: '博士研究生' },
  { value: 'GENERAL', label: '其他 / 通用' },
]

const lifecycleOptions: Array<{
  value: LifecycleStage
  label: string
}> = [
  { value: 'NEW_STUDENT', label: '新生' },
  { value: 'ENROLLED', label: '在读' },
  { value: 'GRADUATING', label: '毕业阶段' },
  { value: 'ALUMNI', label: '校友' },
  { value: 'UNKNOWN', label: '暂不确定' },
]

onMounted(() => {
  void loadProfile()
})

async function loadProfile(): Promise<void> {
  loading.value = true
  profileReady.value = false
  errorMessage.value = ''
  try {
    const profile = await getProfile()
    if (!profile) {
      errorMessage.value = '个人画像暂时无法读取，请稍后重试'
      return
    }
    applyProfile(profile)
  } catch (error) {
    handleFailure(error, '个人画像加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function applyProfile(profile: UserProfileDto): void {
  major.value = profile.major ?? ''
  grade.value = profile.grade === null ? '' : String(profile.grade)
  campus.value = profile.campus ?? ''
  degree.value = profile.degree ?? ''
  college.value = profile.college ?? ''
  lifecycleStage.value = profile.lifecycleStage ?? ''
  courseRecords.value = profile.courseRecords.map((record) => ({
    key: courseKey += 1,
    courseCode: record.courseCode ?? '',
    courseName: record.courseName ?? '',
    term: record.term ?? '',
  }))
  updatedAt.value = profile.updatedAt
  profileReady.value = true
}

function addCourse(): void {
  if (courseLimitReached.value) {
    errorMessage.value = '已达到 100 条课程记录上限'
    successMessage.value = ''
    return
  }
  courseRecords.value.push({
    key: courseKey += 1,
    courseCode: '',
    courseName: '',
    term: '',
  })
}

function removeCourse(index: number): void {
  courseRecords.value.splice(index, 1)
}

async function saveProfile(): Promise<void> {
  if (!profileReady.value || saving.value) {
    return
  }
  if (courseRecords.value.length > MAX_COURSE_RECORDS) {
    errorMessage.value = '课程记录不能超过 100 条'
    successMessage.value = ''
    return
  }

  const normalizedGrade = grade.value.trim()
  const numericGrade = normalizedGrade ? Number(normalizedGrade) : null
  if (numericGrade !== null
      && (!Number.isInteger(numericGrade)
        || numericGrade < 1000
        || numericGrade > 9999)) {
    errorMessage.value = '年级请填写四位年份'
    successMessage.value = ''
    return
  }

  const payload: UpdateProfileRequest = {
    major: nullableText(major.value),
    grade: numericGrade,
    campus: nullableText(campus.value),
    degree: degree.value || null,
    college: nullableText(college.value),
    lifecycleStage: lifecycleStage.value || null,
    courseRecords: courseRecords.value.map(normalizeCourse),
  }

  saving.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const saved = await updateProfile(payload)
    if (!saved) {
      errorMessage.value = '保存响应缺少个人画像，请重试'
      return
    }
    applyProfile(saved)
    successMessage.value = '画像已保存，后续问答会参考这些信息。'
  } catch (error) {
    handleFailure(error, '个人画像保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

function normalizeCourse(record: CourseRecordForm): CourseRecordInput {
  return {
    courseCode: nullableText(record.courseCode),
    courseName: nullableText(record.courseName),
    term: nullableText(record.term),
  }
}

function nullableText(value: string): string | null {
  return value.trim() || null
}

function handleFailure(error: unknown, fallback: string): void {
  if (error instanceof ApiClientError
      && isAuthenticationStatus(error.status)) {
    emit('unauthorized')
    return
  }
  errorMessage.value = error instanceof ApiClientError
    ? error.message
    : fallback
}
</script>

<template>
  <section class="profile-view" aria-labelledby="profile-title">
    <header class="view-heading">
      <div>
        <p>STUDENT FILE · 个人档案</p>
        <h1 id="profile-title">把你的在校阶段，写进答复底稿。</h1>
      </div>
      <div class="file-mark" aria-hidden="true">画像</div>
    </header>

    <p v-if="loading" class="view-status" role="status">
      正在调取个人档案…
    </p>
    <p v-if="errorMessage" class="form-message form-message--error" role="alert">
      {{ errorMessage }}
    </p>
    <button
      v-if="errorMessage && !profileReady"
      class="secondary-action profile-retry"
      type="button"
      data-action="retry-profile"
      :disabled="loading"
      @click="loadProfile"
    >
      重新调取个人档案
    </button>
    <p v-if="successMessage" class="form-message form-message--success" role="status">
      {{ successMessage }}
    </p>

    <form class="profile-form" :aria-busy="saving" @submit.prevent="saveProfile">
      <fieldset
        class="profile-fields"
        data-profile-fields
        :disabled="!profileReady || loading || saving"
      >
        <section class="profile-sheet" aria-labelledby="basic-profile-title">
        <div class="sheet-heading">
          <div>
            <span>卷宗 01</span>
            <h2 id="basic-profile-title">基本学业信息</h2>
          </div>
          <p>空白字段将按“未提供”保存</p>
        </div>

        <div class="field-grid">
          <label>
            <span>专业</span>
            <input v-model="major" name="major" maxlength="64">
          </label>
          <label>
            <span>年级</span>
            <input
              v-model="grade"
              name="grade"
              type="number"
              inputmode="numeric"
              min="1000"
              max="9999"
              placeholder="例如 2026"
            >
          </label>
          <label>
            <span>校区</span>
            <input v-model="campus" name="campus" maxlength="64">
          </label>
          <label>
            <span>培养层次</span>
            <select v-model="degree" name="degree">
              <option value="">未提供</option>
              <option
                v-for="option in degreeOptions"
                :key="option.value"
                :value="option.value"
              >
                {{ option.label }}
              </option>
            </select>
          </label>
          <label>
            <span>学院</span>
            <input v-model="college" name="college" maxlength="64">
          </label>
          <label>
            <span>在校阶段</span>
            <select v-model="lifecycleStage" name="lifecycleStage">
              <option value="">未提供</option>
              <option
                v-for="option in lifecycleOptions"
                :key="option.value"
                :value="option.value"
              >
                {{ option.label }}
              </option>
            </select>
          </label>
        </div>
        </section>

        <section class="profile-sheet" aria-labelledby="course-records-title">
        <div class="sheet-heading sheet-heading--courses">
          <div>
            <span>卷宗 02</span>
            <h2 id="course-records-title">课程记录</h2>
          </div>
          <button
            class="secondary-action"
            type="button"
            data-action="add-course"
            :disabled="courseLimitReached"
            :aria-describedby="courseLimitReached ? 'course-limit-note' : undefined"
            @click="addCourse"
          >
            ＋ 添加课程
          </button>
        </div>

        <p
          v-if="courseLimitReached"
          id="course-limit-note"
          class="limit-note"
          role="status"
        >
          已达到 100 条课程记录上限
        </p>

        <p v-if="courseRecords.length === 0" class="empty-records">
          尚未登记课程。需要课程相关答复时，可在这里补充。
        </p>
        <ol v-else class="course-ledger">
          <li
            v-for="(record, index) in courseRecords"
            :key="record.key"
            data-course-record
          >
            <div class="course-ledger__number" aria-hidden="true">
              {{ String(index + 1).padStart(2, '0') }}
            </div>
            <div class="course-ledger__fields">
              <label :for="`course-code-${record.key}`">
                课程代码
                <input
                  :id="`course-code-${record.key}`"
                  v-model="record.courseCode"
                  name="courseCode"
                  maxlength="64"
                >
              </label>
              <label :for="`course-name-${record.key}`">
                课程名称
                <input
                  :id="`course-name-${record.key}`"
                  v-model="record.courseName"
                  name="courseName"
                  maxlength="128"
                >
              </label>
              <label :for="`course-term-${record.key}`">
                学期
                <input
                  :id="`course-term-${record.key}`"
                  v-model="record.term"
                  name="term"
                  maxlength="64"
                  placeholder="例如 2026 秋"
                >
              </label>
            </div>
            <button
              class="remove-action"
              type="button"
              data-remove-course
              :aria-label="`删除第 ${index + 1} 条课程记录`"
              @click="removeCourse(index)"
            >
              删除
            </button>
          </li>
        </ol>
        </section>

        <footer class="profile-actions">
        <p>
          <template v-if="updatedAt">档案已有更新记录</template>
          <template v-else>档案尚未更新</template>
        </p>
        <button class="primary-action" type="submit" :disabled="saving || loading">
          {{ saving ? '正在归档…' : '保存个人画像' }}
          <span aria-hidden="true">→</span>
        </button>
        </footer>
      </fieldset>
    </form>
  </section>
</template>

<style scoped>
.profile-view {
  max-width: 76rem;
  min-height: calc(100vh - 11rem);
  margin: 0 auto;
  padding: clamp(2rem, 5vw, 4.5rem) clamp(1rem, 4vw, 3.5rem) 5rem;
}

.view-heading {
  position: relative;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 2rem;
  padding-bottom: 1.5rem;
  border-bottom: 5px double var(--green-800);
}

.view-heading p {
  margin: 0 0 0.55rem;
  color: var(--vermilion);
  font-family: Georgia, "Times New Roman", serif;
  font-size: 0.68rem;
  font-weight: 700;
  letter-spacing: 0.18em;
}

.view-heading h1 {
  max-width: 48rem;
  margin: 0;
  color: var(--green-950);
  font-family: STKaiti, KaiTi, "FangSong SC", serif;
  font-size: clamp(2.1rem, 5vw, 4.4rem);
  font-weight: 600;
  line-height: 1.1;
}

.file-mark {
  display: grid;
  flex: 0 0 auto;
  width: 4.2rem;
  height: 4.2rem;
  place-items: center;
  color: var(--vermilion);
  border: 3px double var(--vermilion);
  border-radius: 50%;
  font-family: STKaiti, KaiTi, "FangSong SC", serif;
  transform: rotate(8deg);
}

.view-status,
.form-message {
  margin: 1rem 0 0;
  font-size: 0.82rem;
}

.view-status {
  color: var(--ink-soft);
}

.form-message--error {
  color: #842f25;
}

.form-message--success {
  color: var(--green-700);
  font-weight: 700;
}

.profile-form {
  display: grid;
  margin-top: 1.6rem;
}

.profile-fields {
  display: grid;
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
  gap: 1.4rem;
}

.profile-sheet {
  padding: clamp(1.2rem, 3vw, 2rem);
  background: rgba(248, 242, 226, 0.62);
  border: 1px solid var(--rule);
  box-shadow: 7px 7px 0 rgba(228, 216, 189, 0.62);
}

.sheet-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 1rem;
  padding-bottom: 0.9rem;
  border-bottom: 1px solid var(--rule);
}

.sheet-heading span {
  color: var(--vermilion);
  font-size: 0.66rem;
  font-weight: 800;
  letter-spacing: 0.15em;
}

.sheet-heading h2 {
  margin: 0.25rem 0 0;
  color: var(--green-900);
  font-family: STKaiti, KaiTi, "FangSong SC", serif;
  font-size: 1.55rem;
}

.sheet-heading p {
  margin: 0;
  color: var(--ink-soft);
  font-size: 0.7rem;
}

.field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1.1rem 1.5rem;
  margin-top: 1.25rem;
}

.field-grid label,
.course-ledger__fields label {
  display: grid;
  gap: 0.42rem;
  color: var(--green-800);
  font-size: 0.76rem;
  font-weight: 700;
  letter-spacing: 0.06em;
}

.field-grid input,
.field-grid select,
.course-ledger input {
  width: 100%;
  min-width: 0;
  padding: 0.62rem 0.65rem;
  color: var(--ink);
  background: rgba(255, 255, 255, 0.35);
  border: 1px solid rgba(18, 59, 50, 0.35);
  border-radius: 0;
  outline: 0;
}

.field-grid input:focus-visible,
.field-grid select:focus-visible,
.course-ledger input:focus-visible {
  border-color: var(--vermilion);
  box-shadow: 0 0 0 2px rgba(166, 64, 49, 0.18);
}

.secondary-action {
  padding: 0.55rem 0.75rem;
  color: var(--green-800);
  background: transparent;
  border: 1px solid var(--green-700);
  cursor: pointer;
  font-size: 0.76rem;
}

.secondary-action:disabled {
  cursor: not-allowed;
  opacity: 0.48;
}

.profile-retry {
  margin-top: 0.8rem;
}

.limit-note {
  margin: 0.8rem 0 0;
  color: var(--vermilion);
  font-size: 0.72rem;
}

.empty-records {
  margin: 1.25rem 0 0;
  padding: 1.4rem;
  color: var(--ink-soft);
  text-align: center;
  border: 1px dashed var(--rule);
  font-size: 0.82rem;
}

.course-ledger {
  display: grid;
  gap: 0;
  margin: 0.75rem 0 0;
  padding: 0;
  list-style: none;
}

.course-ledger li {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 1rem;
  align-items: center;
  padding: 1rem 0;
  border-bottom: 1px dotted var(--rule);
}

.course-ledger__number {
  color: var(--vermilion);
  font-family: Georgia, "Times New Roman", serif;
  font-size: 1.2rem;
}

.course-ledger__fields {
  display: grid;
  grid-template-columns: 0.7fr 1.2fr 0.8fr;
  gap: 0.8rem;
}

.remove-action {
  padding: 0.25rem 0;
  color: #842f25;
  background: transparent;
  border: 0;
  border-bottom: 1px dotted currentColor;
  cursor: pointer;
  font-size: 0.7rem;
}

.profile-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 1.2rem;
  padding-top: 0.4rem;
}

.profile-actions p {
  margin: 0;
  color: var(--ink-soft);
  font-size: 0.7rem;
}

.primary-action {
  display: flex;
  justify-content: space-between;
  gap: 2rem;
  min-width: 13rem;
  padding: 0.85rem 1rem;
  color: var(--paper);
  background: var(--green-900);
  border: 1px solid var(--green-900);
  cursor: pointer;
}

.primary-action:disabled {
  cursor: wait;
  opacity: 0.55;
}

@media (max-width: 700px) {
  .field-grid,
  .course-ledger__fields {
    grid-template-columns: 1fr;
  }

  .sheet-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .course-ledger li {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .remove-action {
    grid-column: 2;
    justify-self: start;
  }

  .profile-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .primary-action {
    width: 100%;
  }
}

@media (max-width: 440px) {
  .file-mark {
    display: none;
  }
}
</style>
