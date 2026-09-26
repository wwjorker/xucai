<script setup>
const steps = [
  {
    title: "普通选购：选好后直接下单",
    buyer: "浏览设备，进入详情选择数量并加入采购清单。在清单中核对商品、填写收货信息，再由你确认订单，无需先填写需求单。",
    merchant: "维护商品、售价与库存，在订单中处理客户已经确认的采购。",
    path: "/",
    link: "开始选购设备",
  },
  {
    title: "AI协助：直接说出你想要什么",
    buyer: "登录后打开右下角的序采助手。可以问“预算200元，买四个办公鼠标”，也可以在商品详情点击“问问AI”。助手会找商品、查资料，并按你的指示修改清单；最终订单仍由你确认。",
    merchant: "商品参数与有效资料是助手回答的依据；资料不足时，需要进一步核实。",
    path: "/cart",
    link: "核对我的采购清单",
  },
  {
    title: "复杂采购：请商家搭配方案",
    buyer: "涉及多类设备、兼容性或批量搭配时，可以请助手整理需求草稿，也可以自行填写。保存后商家才能查看；待商家发布方案后，再比较、核对并确认订单。",
    merchant: "根据客户需求选择设备、引用资料，逐项核实后发布方案。找到原文不等于已经满足采购条件。",
    path: "/requirements/new",
    link: "填写复杂采购需求",
  },
  {
    title: "跟进履约与售后",
    buyer: "在订单中演示支付或取消，发货后确认收货；有问题可提交售后申请。",
    merchant:
      "登记物流、处理客户售后并保存处理说明。售后结论不自动退款、补发或回库。",
    path: "/purchase-orders",
    link: "查看我的订单",
  },
];
</script>

<template>
  <div class="page-container guide-page">
    <section class="guide-heading">
      <p class="eyebrow">序采使用指南</p>
      <h1>直接选购，也可以让AI帮你。</h1>
      <p>
        普通设备加入清单后即可确认下单；不确定怎么选，先问助手；复杂搭配再交给商家核实。
      </p>
      <div class="guide-links">
        <RouterLink to="/">浏览设备</RouterLink
        ><RouterLink to="/merchant/products">进入商家工作台 →</RouterLink>
      </div>
    </section>
    <ol class="guide-steps">
      <li v-for="(step, index) in steps" :key="step.title">
        <span class="step-number">0{{ index + 1 }}</span>
        <h2>{{ step.title }}</h2>
        <h3>采购客户</h3>
        <p>{{ step.buyer }}</p>
        <h3>商家</h3>
        <p>{{ step.merchant }}</p>
        <RouterLink :to="step.path">{{ step.link }} →</RouterLink>
      </li>
    </ol>
    <section class="guide-boundaries" aria-labelledby="guide-boundaries-title">
      <h2 id="guide-boundaries-title">开始前，请了解这些约定</h2>
      <ul>
        <li>
          这是演示环境。价格、库存和支付均用于流程演示，不真实收款，也不代表实际供货。
        </li>
        <li>
          商品资料注明来源及适用地区。实际选购前请复核准确型号、供电、接口和系统兼容性。
        </li>
        <li>
          助手可以推荐设备、查询资料、调整清单和查询本人订单。不会替你确认订单、付款或取消订单；复杂方案仍需商家核实。
        </li>
        <li>
          未支付订单可取消，超过支付期限会自动关闭并归还预留库存。普通选购可重新加入清单；复杂方案请返回需求页查看可用方案。
        </li>
        <li>
          资料或需求更正后，旧方案保留历史内容，但可能无法继续确认；请查看页面中的最新检查结果。
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.guide-page {
  padding-bottom: 64px;
}
.guide-heading {
  max-width: 760px;
  padding: 48px 0 32px;
}
.guide-heading h1 {
  font-size: clamp(28px, 4vw, 42px);
  line-height: 1.3;
  margin: 16px 0;
}
.guide-heading p,
.guide-steps p,
.guide-boundaries li {
  line-height: 1.8;
  color: #52635c;
}
.guide-links {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  margin-top: 24px;
}
.guide-page a {
  color: #214e40;
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 4px;
}
.guide-steps {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24px;
}
.guide-steps li {
  border: 1px solid #dce5df;
  border-radius: 16px;
  background: white;
  padding: 28px;
}
.step-number {
  color: #6c897a;
  font-size: 14px;
  letter-spacing: 0.12em;
}
.guide-steps h2 {
  font-size: 22px;
  margin: 12px 0 24px;
}
.guide-steps h3 {
  font-size: 14px;
  margin: 20px 0 4px;
}
.guide-steps p {
  margin: 0 0 20px;
}
.guide-boundaries {
  margin-top: 32px;
  padding: 28px;
  background: #edf2ee;
  border-radius: 16px;
}
.guide-boundaries h2 {
  font-size: 20px;
  margin-top: 0;
}
.guide-boundaries ul {
  padding-left: 20px;
  margin-bottom: 0;
}
.guide-boundaries li + li {
  margin-top: 10px;
}
@media (max-width: 640px) {
  .guide-steps {
    grid-template-columns: 1fr;
    gap: 16px;
  }
  .guide-steps li,
  .guide-boundaries {
    padding: 20px;
  }
  .guide-heading {
    padding-top: 28px;
  }
}
</style>
