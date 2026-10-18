<script setup lang="ts">
import type { Material } from '../types'
import type { InterviewConfig } from './types'
const config = defineModel<InterviewConfig>({ required: true })
defineProps<{ materials: Material[]; disabled?: boolean; correction?: boolean; legacy?: boolean }>()
</script>

<template>
  <fieldset :disabled="disabled" class="v3-fields">
    <legend class="sr-only">本场面试配置</legend>
    <div class="form-grid">
      <div class="form-field">
        <label for="v3-role">目标岗位 <span class="muted">（必填）</span></label>
        <select id="v3-role" v-model="config.role" required>
          <option value="" disabled>请选择岗位方向</option>
          <option>Java 后端工程师</option><option>大模型应用 / Agent 开发工程师</option>
          <option v-if="config.role && !['Java 后端工程师', '大模型应用 / Agent 开发工程师'].includes(config.role)" :value="config.role">{{ config.role }}</option>
        </select>
      </div>
      <div class="form-field">
        <label for="v3-level">目标职级 <span class="muted">（必填）</span></label>
        <select id="v3-level" v-model="config.level" required><option value="" disabled>请选择职级</option><option value="INTERN">实习生</option><option value="JUNIOR">初级</option><option value="SENIOR">中高级</option></select>
      </div>
    </div>
    <div class="form-field">
      <label for="v3-resume">选择简历 <span class="muted">（可选）</span></label>
      <select id="v3-resume" v-model="config.resumeId">
        <option value="">不使用简历</option>
        <option v-for="item in materials.filter(item => item.kind === 'RESUME')" :key="item.id" :value="item.id">{{ item.name }}</option>
        <option v-if="config.resumeId && !materials.some(item => item.id === config.resumeId)" :value="config.resumeId">本场已选简历</option>
      </select>
      <p class="form-help">围绕实际职责、业务场景、方案权衡和异常处理展开。<RouterLink to="/materials?kind=RESUME" class="text-link">管理简历资料</RouterLink></p>
      <p v-if="materials.find(item => item.id === config.resumeId)?.documents?.some(item => item.truncated)" class="form-help">所选简历存在截断附件，本场仅使用保留的文本。</p>
    </div>
    <div class="form-field">
      <label for="v3-jd-id">选择岗位描述 <span class="muted">（可选）</span></label>
      <select id="v3-jd-id" v-model="config.jdId" @change="config.jd = ''">
        <option value="">不使用已保存的 JD</option>
        <option v-for="item in materials.filter(item => item.kind === 'JD')" :key="item.id" :value="item.id">{{ item.name }}</option>
        <option v-if="config.jdId && !materials.some(item => item.id === config.jdId)" :value="config.jdId">本场已选 JD</option>
      </select>
    </div>
    <div class="form-field">
      <label for="v3-jd-text">{{ correction ? '替换本场补充 JD' : '或粘贴岗位描述' }} <span class="muted">（可选）</span></label>
      <textarea id="v3-jd-text" v-model="config.jd" maxlength="10000" :placeholder="correction ? '留空保留原有补充 JD；输入后替换本场 JD。' : '描述目标岗位的要求，帮助确定经历与岗位的匹配程度。'" @input="config.jdId = ''"></textarea>
      <p class="form-help">简历和 JD 会发送到你配置的模型服务。公开检索仅使用泛化技术概念，不外发简历原文。</p>
    </div>
    <template v-if="!legacy">
      <div class="form-field">
        <label for="v3-project">本场主项目 <span class="muted">（可选）</span></label>
        <select id="v3-project" v-model="config.projectId">
          <option value="">从简历识别，或在下面简述</option>
          <option v-for="item in materials.filter(item => item.kind === 'PROJECT')" :key="item.id" :value="item.id">{{ item.name }}</option>
          <option v-if="config.projectId && !materials.some(item => item.id === config.projectId)" :value="config.projectId">本场已选项目</option>
        </select>
      </div>
      <div class="form-field">
        <label for="v3-project-brief">简述或补充主项目 <span class="muted">（可选）</span></label>
        <textarea id="v3-project-brief" v-model="config.projectBrief" maxlength="5000" placeholder="项目做什么、你具体负责什么、遇到过哪些问题。请只填写真实经历。"></textarea>
        <p class="form-help">每场聚焦一个项目，准备完成后可以纠偏；没有真实项目时使用假设场景，不生成真实经历证据矩阵。</p>
      </div>
      <div v-if="!correction" class="form-field">
        <label for="v3-mode">面试模式</label>
        <select id="v3-mode" v-model="config.interviewMode"><option value="TRAINING">不限时训练</option><option value="TIMED">计时实战</option></select>
        <p class="form-help">开场介绍 → 项目深挖 → 岗位技术与场景 → 候选人反问。</p>
      </div>
      <div v-if="!correction && config.interviewMode === 'TIMED'" class="form-field">
        <label for="v3-time-limit">实战时长</label>
        <select id="v3-time-limit" v-model.number="config.timeLimitMinutes"><option :value="20">20 分钟</option><option :value="30">30 分钟</option><option :value="45">45 分钟</option></select>
        <p class="form-help">确认开考后计时，模型生成和系统等待不扣时；到点保留当前答案，校对后手动提交。</p>
      </div>
    </template>
    <div v-if="!correction && !legacy" class="form-field">
      <label for="v3-question-count">计划题量</label>
      <select id="v3-question-count" v-model.number="config.plannedQuestionCount"><option :value="8">计划 8 题</option><option :value="12">计划 12 题</option></select>
      <p class="form-help">整场四阶段共用的主问题规划，实际题数会根据回答深度、追问和剩余时间调整，不要求固定答满。</p>
    </div>
    <div v-if="!correction && legacy && config.interviewMode !== 'TIMED'" class="form-field">
      <label for="v3-duration">训练题量</label>
      <select id="v3-duration" v-model.number="config.durationMinutes"><option :value="5">5 分钟</option><option :value="10">10 分钟</option><option :value="20">20 分钟</option></select>
      <p class="form-help">沿用短练习的题量档位，不设倒计时；四阶段各保留一次作答机会，可随时结束并进入复盘。</p>
    </div>
  </fieldset>
</template>

<style scoped>
.v3-fields { border: 0; min-width: 0; padding: 0; margin: 0; }
.v3-fields label .muted { font-weight: 400; }
.v3-fields .text-link { margin-left: 8px; }
.v3-fields select, .v3-fields textarea { max-width: 100%; }
.v3-fields :is(select, textarea):focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; border: 0; }
</style>
