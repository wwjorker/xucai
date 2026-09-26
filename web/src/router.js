import { createRouter, createWebHistory } from "vue-router";
import { ensureSession } from "./api";
export const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    {
      path: "/cart",
      component: () => import("./pages/CartPage.vue"),
      meta: { buyer: true },
    },
    {
      path: "/checkout/:id",
      component: () => import("./pages/CheckoutPage.vue"),
      meta: { buyer: true },
    },
    {
      path: "/design/assistant",
      component: () => import("./pages/AssistantPreviewPage.vue"),
      meta: { standalone: true },
    },
    {
      path: "/guide",
      name: "procurement-guide",
      component: () => import("./pages/ProcurementGuide.vue"),
    },
    {
      path: "/plans/:id/confirm",
      component: () => import("./pages/OrderConfirmPage.vue"),
      meta: { buyer: true },
    },
    {
      path: "/purchase-orders",
      component: () => import("./pages/PurchaseOrdersPage.vue"),
      meta: { buyer: true },
    },
    {
      path: "/purchase-orders/:id",
      component: () => import("./pages/PurchaseOrdersPage.vue"),
      meta: { buyer: true },
    },
    {
      path: "/merchant/purchase-orders",
      component: () => import("./pages/PurchaseOrdersPage.vue"),
      meta: { merchant: true },
    },
    {
      path: "/merchant/purchase-orders/:id",
      component: () => import("./pages/PurchaseOrdersPage.vue"),
      meta: { merchant: true },
    },
    {
      path: "/merchant/plans/:id/review",
      name: "plan-review",
      component: () => import("./pages/PlanReviewPage.vue"),
      meta: { merchant: true },
    },
    {
      path: "/merchant/plans/:id/released",
      name: "plan-released",
      component: () => import("./pages/PlanReviewPage.vue"),
      meta: { merchant: true },
    },
    {
      path: "/plans/:id",
      name: "buyer-plan",
      component: () => import("./pages/PlanReviewPage.vue"),
      meta: { buyer: true },
    },
    {
      path: "/requirements/:id/plans",
      name: "buyer-plans",
      component: () => import("./pages/BuyerPlansPage.vue"),
      meta: { buyer: true },
    },
    {
      path: "/merchant/requirements/:id/plans",
      name: "merchant-plans",
      component: () => import("./pages/PlanDraftPage.vue"),
      meta: { merchant: true },
    },
    {
      path: "/requirements",
      name: "requirements",
      component: () => import("./pages/RequirementList.vue"),
      meta: { buyer: true },
    },
    {
      path: "/requirements/new",
      name: "requirement-new",
      component: () => import("./pages/RequirementPage.vue"),
      meta: { buyer: true },
    },
    {
      path: "/requirements/from-assistant/:session/:turn",
      component: () => import("./pages/RequirementPage.vue"),
      meta: { buyer: true },
    },
    {
      path: "/requirements/:id",
      name: "requirement",
      component: () => import("./pages/RequirementPage.vue"),
      meta: { buyer: true },
    },
    {
      path: "/merchant/requirements",
      name: "merchant-requirements",
      component: () => import("./pages/RequirementList.vue"),
      meta: { merchant: true },
    },
    {
      path: "/merchant/requirements/:id",
      name: "merchant-requirement",
      component: () => import("./pages/RequirementPage.vue"),
      meta: { merchant: true },
    },
    {
      path: "/",
      name: "catalog",
      component: () => import("./pages/CatalogPage.vue"),
    },
    {
      path: "/products/:id",
      name: "product",
      component: () => import("./pages/ProductPage.vue"),
    },
    {
      path: "/login",
      name: "buyer-login",
      component: () => import("./pages/LoginPage.vue"),
    },
    {
      path: "/merchant/login",
      name: "merchant-login",
      component: () => import("./pages/LoginPage.vue"),
    },
    { path: "/merchant", redirect: "/merchant/products" },
    {
      path: "/merchant/doc-chunks",
      name: "merchant-doc-chunks",
      component: () => import("./pages/DocChunkPage.vue"),
      meta: { merchant: true },
    },
    {
      path: "/merchant/retrieval",
      name: "merchant-retrieval",
      component: () => import("./pages/RetrievalPage.vue"),
      meta: { merchant: true },
    },
    {
      path: "/merchant/products",
      name: "merchant-list",
      component: () => import("./pages/MerchantList.vue"),
      meta: { merchant: true },
    },
    {
      path: "/merchant/products/new",
      name: "merchant-new",
      component: () => import("./pages/ProductEditor.vue"),
      meta: { merchant: true },
    },
    {
      path: "/merchant/products/:id/edit",
      name: "merchant-edit",
      component: () => import("./pages/ProductEditor.vue"),
      meta: { merchant: true },
    },
    {
      path: "/:pathMatch(.*)*",
      component: () => import("./pages/NotFound.vue"),
    },
  ],
});
router.beforeEach(async (to) => {
  if (to.meta.buyer && !(await ensureSession("buyer")))
    return { name: "buyer-login", query: { next: to.fullPath } };
  if (to.meta.merchant && !(await ensureSession("merchant")))
    return { name: "merchant-login", query: { next: to.fullPath } };
});
