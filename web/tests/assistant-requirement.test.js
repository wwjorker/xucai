import test from "node:test";
import assert from "node:assert/strict";
import { assistantRequirementForm } from "../src/assistantRequirement.js";

test("assistant draft prefills known values and preserves unknown quantities", () => {
  const t={status:"DONE",result:{requirementDraft:{title:"会议室",rawText:"兼容性待核实",structured:{budgetCents:12345,items:[{categoryId:5,quantity:null}],usage:"会议",mustHave:["不需要无线"],optional:[]}}}};
  const f=assistantRequirementForm(t);assert.equal(f.budget,"123.45");assert.equal(f.items[0].quantity,undefined);assert.equal(f.mustHave,"不需要无线");
  f.items[0].quantity=2;assert.equal(t.result.requirementDraft.structured.items[0].quantity,null);
});
test("failed or absent draft cannot become a requirement",()=>{
  for(const t of [null,{status:"FAILED",result:{requirementDraft:{title:"x"}}},{status:"DONE",result:{}}])assert.throws(()=>assistantRequirementForm(t));
});
