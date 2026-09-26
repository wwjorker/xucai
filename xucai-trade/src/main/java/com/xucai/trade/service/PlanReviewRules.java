package com.xucai.trade.service;
import com.xucai.api.dto.mall.*;
import com.xucai.trade.domain.dto.PlanReviewForm;
import com.xucai.common.exceptions.BusinessException;
import java.util.*;
public final class PlanReviewRules {
    private PlanReviewRules() { }
    private static final String[] NAMES={"","显示器","打印机","鼠标","键盘","摄像头","耳机"};
    public record Condition(String key,String text) { }
    public record CheckedReference(Long productId,String productName,Long docId,Long version,String title,String quote) { }
    public record CheckedCondition(String key,String text,String verdict,String reason,List<CheckedReference> references,String assessedBy) { }
    public record Review(String note,List<CheckedCondition> checks) { }
    public static List<Condition> conditions(PlanDraftRules.Snapshot snapshot) {
        List<Condition> result=new ArrayList<>();var d=snapshot.requirement().structured();
        result.add(new Condition("usage",d.usage()));
        for(int i=0;i<d.mustHave().size();i++)result.add(new Condition("mustHave:"+i,d.mustHave().get(i)));
        return List.copyOf(result);
    }
    public static Review review(PlanDraftRules.Snapshot snapshot,PlanReviewForm form) {
        var expected=conditions(snapshot);Map<String,PlanReviewForm.Check> checks=new HashMap<>();
        for(var check:form.checks())if(checks.put(check.key(),check)!=null)throw invalid("核实项不能重复");
        if(!checks.keySet().equals(new HashSet<>(expected.stream().map(Condition::key).toList())))throw invalid("必须逐项核实用途与所有必须条件，不能遗漏或添加未知条件");
        List<CheckedCondition> result=new ArrayList<>();
        for(var condition:expected) {
            var check=checks.get(condition.key());
            if(!"MET".equals(check.verdict()))throw new BusinessException(409,"仍有未确认或冲突条件，不能发布待确认版本");
            if(check.references().isEmpty())throw invalid("每项判断必须引用草稿中已保存的摘录");
            Set<String> seen=new HashSet<>();List<CheckedReference> refs=new ArrayList<>();
            for(var ref:check.references()) {
                if(!seen.add(ref.productId()+":"+ref.docId()))throw invalid("同一核实项不能重复引用");
                var item=snapshot.items().stream().filter(i->i.productId().equals(ref.productId())).findFirst().orElseThrow(()->invalid("引用商品不在此草稿中"));
                var e=item.evidence().stream().filter(v->v.docId().equals(ref.docId())).findFirst().orElseThrow(()->invalid("引用必须指向此商品在草稿中保存的摘录"));
                refs.add(new CheckedReference(item.productId(),item.name(),e.docId(),e.version(),e.title(),e.quote()));
            }
            result.add(new CheckedCondition(condition.key(),condition.text(),"MET",check.reason().trim(),List.copyOf(refs),"MERCHANT"));
        }
        return new Review(form.note().trim(),List.copyOf(result));
    }
    public static List<String> business(PlanDraftRules.Snapshot snapshot,Map<Long,SaleItemDTO> sales) {
        List<String> reasons=new ArrayList<>();var requirement=snapshot.requirement();
        for(var missing:RequirementRules.check(requirement).missing())reasons.add(missing.message());
        if(requirement.structured().budgetCents()!=null && PlanDraftRules.total(snapshot.items())>requirement.structured().budgetCents())reasons.add("方案总额超过预算");
        Map<Integer,Integer> actual=new TreeMap<>(),expected=new TreeMap<>();
        snapshot.items().forEach(i->actual.merge(i.categoryId(),i.quantity(),Integer::sum));
        for(var r:RequirementRules.itemsForRead(requirement.structured()))if(r.quantity()!=null)expected.put(r.categoryId(),r.quantity());
        if(!expected.equals(actual))reasons.add("方案的分类和各类件数必须与需求完全一致");
        for(var item:snapshot.items()) {
            var sale=sales.get(item.productId());
            if(sale==null || !Boolean.TRUE.equals(sale.onSale()))reasons.add(item.name()+"：已停售或不存在售卖信息");
            else {
                if(!sale.price().equals(item.unitPrice()) || !sale.saleVersion().equals(item.saleVersion()))reasons.add(item.name()+"：价格或售卖版本已变化，请重新编制");
                if(sale.stock()<item.quantity())reasons.add(item.name()+"：库存不足");
            }
            if(item.evidence().isEmpty())reasons.add(item.name()+"：缺少资料摘录，请补充新草稿");
        }
        return List.copyOf(reasons);
    }
    public static List<String> catalog(PlanDraftRules.Snapshot snapshot,Map<Long,CatalogSnapshotDTO> products) {
        List<String> reasons=new ArrayList<>();
        for(var item:snapshot.items()) {
            var p=products.get(item.productId());
            if(p==null || !"PUBLISHED".equals(p.status())){reasons.add(item.name()+"：商品不可用");continue;}
            if(!p.specVersion().equals(item.specVersion()))reasons.add(item.name()+"：规格已有更正，需重新编制并核实");
            for(var e:item.evidence())if(p.documents().stream().noneMatch(d->d.id().equals(e.docId())&&d.version().equals(e.version())&&d.content().equals(e.content())&&d.content().contains(e.quote())))reasons.add(item.name()+"：引用资料已更正或退役，需重新核实");
        }
        return List.copyOf(reasons);
    }
    private static BusinessException invalid(String message){return new BusinessException(400,message);}
}
