package com.xucai.aigc.procurement.chat;

import java.util.*;
import java.util.regex.Pattern;

/** Conservative purchase-write checks. Conversational intent is handled by model routing. */
final class AssistantIntentRules {
    private AssistantIntentRules() { }
    static String compact(String text) { return text.replaceAll("[\\s，。！？,.!?]", ""); }
    static boolean categoryShopping(String text) {
        String t=compact(text);
        return t.matches(".*(?:想买|要买|买|选购|推荐).*?(?:鼠标|键盘|显示器|打印机|摄像头|耳机).*")
            && !t.matches(".*(?:清单|订单|商家|方案|草稿|第[一二三四五六七八1-8]款|选项[1-8]).*");
    }
    static int number(String n) {
        if(n.matches("[0-9]{1,3}"))return Integer.parseInt(n);
        if(n.equals("两"))return 2;
        String digits="零一二三四五六七八九";
        if(n.length()==1&&digits.indexOf(n)>=0)return digits.indexOf(n);
        if(n.matches("[一二三四五六七八九]?十[一二三四五六七八九]?")){
            int i=n.indexOf('十');return (i==0?1:digits.indexOf(n.charAt(0)))*10+(i==n.length()-1?0:digits.indexOf(n.charAt(i+1)));
        }
        return -1;
    }
    static boolean catalogBrowsing(String text) {
        String t=compact(text);
        if(t.matches(".*(?:不要|不用|别|不想|清单|订单|下单|如果|假如).*") )return false;
        String category="(?:鼠标|键盘|显示器|打印机|摄像头|耳机|商品|设备)";
        return t.matches("(?:请问|请|目前|现在|你们|店里|平台|这里|都)*(?:有哪些|有什么|有啥).{0,16}"+category+"(?:可选|在售|可以买|吗|呢)?")
            || t.matches("(?:请|给我|让我|帮我)*(?:看看|看一下|列一下|列出|展示一下|浏览).{0,16}"+category+"(?:列表|目录|有哪些|有什么)?");
    }
    static int catalogCategory(String text) {
        // Matches the catalogue's established category IDs, not product IDs or model guesses.
        String[] names={"显示器","打印机","鼠标","键盘","摄像头","耳机"};
        int category=0;
        for(int i=0;i<names.length;i++)if(text.contains(names[i])){
            if(category!=0)return 0;
            category=i+1;
        }
        return category;
    }
    static Long budget(List<String> sources) {
        Long result=null;
        for(String text:sources){
            var match=Pattern.compile("(?:总)?预算(?:是|为|大约|约|[:：])?\\s*([0-9]{1,7}(?:\\.[0-9]{1,2})?)\\s*(?:元|以内|以下|(?=[，,。\\s]|$))|([0-9]{1,7}(?:\\.[0-9]{1,2})?)\\s*元\\s*(?:以内|以下|之内)").matcher(text);
            while(match.find())result=new java.math.BigDecimal(match.group(1) != null ? match.group(1) : match.group(2)).movePointRight(2).longValueExact();
        }
        return result;
    }
    record Mutation(int position,boolean relative,int amount) { }
    // Resolve a reference only after semantic routing. This never decides whether to buy or read.
    static int referencedPosition(String text, int size) {
        var matcher=Pattern.compile("(?:第\\s*([一二三四五六七八1-8])\\s*(?:款|个)|选项\\s*([一二三四五六七八1-8]))").matcher(text);
        int selected=0;
        while(matcher.find()) {
            int position=number(matcher.group(1)==null?matcher.group(2):matcher.group(1));
            if(position<1 || position>size || selected!=0 && selected!=position) return 0;
            selected=position;
        }
        return selected;
    }
    static String normalizePurchase(String text) {
        String t=compact(text);
        return t.replaceFirst("^(?:我)?(?:想买|要买|买)(选项[一二三四五六七八1-8]|第[一二三四五六七八1-8](?:款|个))", "$1要");
    }
    static Mutation mutation(String text,List<Map<String,Object>> cards) {
        if(text.contains("订单"))return null; // Compound operations remain in the bounded specialist loop.
        var m=Pattern.compile("(?:第|选项)([一二三四五六七八1-8])(?:款|个)?.*?(再加|增加|加入采购清单|加入清单|加|减少|减|要|买|设为|改成|改为|设置为)([0-9]{1,3}|[零一二两三四五六七八九十]{1,3})").matcher(normalizePurchase(text));
        if(!m.find())return null;
        int position=number(m.group(1)),amount=number(m.group(3));
        boolean relative=m.group(2).matches("再加|增加|加|减少|减");
        if(m.group(2).matches("减少|减"))amount=-amount;
        return authorizes(text,cards,position,relative,amount)?new Mutation(position,relative,amount):null;
    }
    // Accepted commands are deliberately narrow. Complex or negative requests need an explicit follow-up.
    static boolean authorizes(String text,List<Map<String,Object>> originalCards,int position,boolean relative,int amount) {
        if(position<1||position>originalCards.size())return false;
        // A question is not an instruction, even when punctuation normalization would make it look like one.
        if(text.contains("?")||text.contains("？"))return false;
        String t=normalizePurchase(text);
        t=t.replaceAll("(?:并|然后)?(?:请)?(?:查看|看看|查询)(?:我的)?订单$", "");
        t=t.replaceAll("(?:但)?(?:不要|不用)下单$", "");
        // Only remove a redundant destination after an explicit quantity command.
        // Negation/conditions remain in the input and fail the full-command match below.
        t=t.replaceAll("(?:并|然后)?(?:请)?(?:放进|放入|加入)(?:我的)?(?:采购)?清单$", "");
        String ordinal="(?:第|选项)([一二三四五六七八1-8])(?:款|个)?";
        String n="([0-9]{1,3}|[零一二两三四五六七八九十]{1,3})";
        String operation=relative?"(?:再加|增加|加|减少|减)":"(?:要|买|加入清单|加入采购清单|设为|改成|改为|设置为)";
        var m=Pattern.compile("(?:请)?(?:把)?"+ordinal+"(?:在(?:我的)?(?:采购)?清单里)?("+operation+")"+n+"(?:件|个|台|副)?").matcher(t);
        if(!m.matches()||number(m.group(1))!=position)return false;
        int quantity=number(m.group(3));
        if(quantity<0)return false;
        if(relative&&m.group(2).matches("减少|减"))quantity=-quantity;
        return amount==quantity;
    }
}
