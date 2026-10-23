import { createRouter, createWebHistory } from 'vue-router'
import WorkbenchView from './views/WorkbenchView.vue'
import NotFoundView from './views/NotFoundView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/workbench' },
    { path: '/workbench', name: 'workbench', component: WorkbenchView },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFoundView },
  ],
})
