package ru.repaircalc.calculation;

import java.math.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import ru.repaircalc.catalog.ProductView;
import ru.repaircalc.calculation.domain.*;
import ru.repaircalc.rules.RuleSetBundle;
import ru.repaircalc.common.web.ApiStatusException;

@Service
public class ProjectCalculationService {
    public static final String EDUCATIONAL="ТЕСТОВЫЕ товары, цены, нормы и тарифы. Это не коммерческая смета.";
    private final CalculationSnapshotService snapshots;private final MaterialCalculator calculator;private final Clock clock;
    public ProjectCalculationService(CalculationSnapshotService snapshots,MaterialCalculator calculator,Clock clock){this.snapshots=snapshots;this.calculator=calculator;this.clock=clock;}

    public static boolean compatible(ProductView p,ProjectInput.Room r){
        return ("normal".equals(r.humidity()) || Boolean.TRUE.equals(p.wet()))
            && ("normal".equals(r.load()) || Boolean.TRUE.equals(p.highLoad()))
            && (r.heated() || Boolean.TRUE.equals(p.unheated()));
    }
    public List<ProductView> candidates(ProjectInput.Room room,CalculationSnapshotService.Snapshot snapshot){
        var assignments=new HashMap<UUID,RuleSetBundle.Assignment>();snapshot.rules().bundle().assignments().forEach(a->assignments.put(a.productId(),a));
        return snapshot.catalog().products().stream().filter(p->compatible(p,room)&&p.works().stream().anyMatch(room.works()::contains))
            .filter(p->assignments.containsKey(p.id())&&assignments.get(p.id()).profileId()!=null)
            .sorted(Comparator.comparingInt((ProductView p)->assignments.get(p.id()).priority()).thenComparing(p->p.id().toString())).toList();
    }
    public Recommendations recommend(ProjectInput.Room room){
        room.validate();var snapshot=snapshots.resolve();
        return new Recommendations(candidates(room,snapshot).stream().map(p->new Recommendation(p,"Совместимость и профиль подтверждены для заданных условий.")).toList(),List.of(EDUCATIONAL),"ok");
    }
    public ProductEstimate estimate(ProjectInput project,Map<String,String> selections){return estimate(project,selections,snapshots.resolve());}
    public ProductEstimate estimate(ProjectInput project,Map<String,String> selections,CalculationSnapshotService.Snapshot snapshot){
        project.validate();var selected=selections==null?Map.<String,String>of():selections;
        if(selected.size()>140)throw invalidSelection();
        var consumed=new HashSet<String>();var lines=new ArrayList<ProductEstimate.Line>();var warnings=new LinkedHashSet<String>();
        warnings.add(EDUCATIONAL);warnings.add("Подготовка основания, демонтаж, доставка, гидроизоляция, подложка, плинтусы и инструменты не включены.");
        warnings.add("Тип объекта и класс ремонта — контекст; неподтверждённые наценки не применяются.");
        var bundle=snapshot.rules().bundle();if(bundle.kits().isEmpty())throw CalculationException.needsClarification("Work kits are unavailable.","rules");
        BigDecimal labor=BigDecimal.ZERO;
        for(var room:project.rooms()){
            var candidates=candidates(room,snapshot);ProductView covering=null;
            if(room.works().contains("floor"))covering=choose(room,"covering",candidates,selected,consumed);
            var kinds=new HashSet<String>();
            for(var kit:bundle.kits()){
                if(!room.works().contains(kit.work()))continue;
                if(kit.coveringFamily()!=null&&(covering==null||!kit.coveringFamily().equals(covering.materialFamily())))continue;
                if(kit.basis()==RuleSetBundle.Basis.SOCKET&&room.sockets()==0 || kit.basis()==RuleSetBundle.Basis.CABLE&&room.cableLength().signum()==0)continue;
                if(!kinds.add(kit.kind()))continue;
                var product=kit.kind().equals("covering")?covering:choose(room,kit.kind(),candidates,selected,consumed);
                if(product==null)throw CalculationException.needsClarification("Missing required material: "+kit.kind(),"products");
                if(product.kind().equals("covering")&&product.materialFamily()==null)throw CalculationException.needsClarification("Confirm covering material family.","materialFamily");
                BigDecimal area=kit.basis()==RuleSetBundle.Basis.FLOOR?room.floor():room.walls();
                BigDecimal quantity=switch(kit.basis()){case SOCKET->BigDecimal.valueOf(room.sockets());case CABLE->room.cableLength();default->BigDecimal.ONE;};
                var input=new CalculationInput(area,room.perimeter(),room.height(),room.reserve(),BigDecimal.valueOf(room.layers()),quantity);
                var assignment=bundle.assignments().stream().filter(a->a.productId().equals(product.id())).findFirst().orElseThrow();
                var profile=bundle.profiles().stream().filter(p->p.id().equals(assignment.profileId())).findFirst().orElseThrow(()->CalculationException.needsClarification("Required profile unavailable.","profile"));
                String source=bundle.sources().stream().filter(s->s.id().equals(profile.sourceId())).findFirst().orElseThrow().description();
                var result=calculator.calculate(new CalculationProduct(product.id(),product.kind(),new BigDecimal(product.price()),product.packageSize(),product.unit(),product.saleUnit(),product.consumption(),product.rollWidth(),product.rollLength(),product.rapport()),input,profile,source);
                var a=result.packages();lines.add(new ProductEstimate.Line(room.id()+":"+product.kind(),room.id(),room.name(),product,a.quantity(),a.need(),a.purchased(),a.remainder(),a.total().amount().toPlainString(),result.formula(),result.reason()));
                if(kit.warning()!=null&&!kit.warning().isBlank())warnings.add(kit.warning());
            }
            for(String work:room.works()){
                if(work.equals("electrical")){
                    if(room.sockets()>0)labor=labor.add(tariff(bundle,"socket").multiply(BigDecimal.valueOf(room.sockets())));
                    if(room.cableLength().signum()>0)labor=labor.add(tariff(bundle,"cable").multiply(room.cableLength()));
                }else labor=labor.add(tariff(bundle,work).multiply(work.equals("floor")?room.floor():room.walls()));
            }
        }
        if(!consumed.equals(selected.keySet()))throw invalidSelection();
        BigDecimal materials=lines.stream().map(l->new BigDecimal(l.total())).reduce(BigDecimal.ZERO,BigDecimal::add);
        labor=labor.setScale(2,RoundingMode.HALF_UP);
        return new ProductEstimate(UUID.randomUUID(),clock.instant(),"api",lines,materials.setScale(2).toPlainString(),labor.toPlainString(),materials.add(labor).setScale(2).toPlainString(),List.copyOf(warnings),new ProductEstimate.Versions(snapshot.catalog().version(),snapshot.rules().version(),"not-used"));
    }
    private ProductView choose(ProjectInput.Room room,String kind,List<ProductView> candidates,Map<String,String> selected,Set<String> consumed){
        String key=room.id()+":"+kind,id=selected.get(key);
        if(selected.containsKey(key)){consumed.add(key);return candidates.stream().filter(p->p.kind().equals(kind)&&p.id().toString().equals(id)).findFirst().orElseThrow(ProjectCalculationService::invalidSelection);}
        return candidates.stream().filter(p->p.kind().equals(kind)).findFirst().orElseThrow(()->CalculationException.needsClarification("Missing required material: "+kind,"products"));
    }
    private BigDecimal tariff(RuleSetBundle bundle,String code){return bundle.laborTariffs().stream().filter(t->t.code().equals(code)).findFirst().orElseThrow(()->CalculationException.needsClarification("Labor tariff missing: "+code,"laborTariffs")).amount();}
    private static ApiStatusException invalidSelection(){return new ApiStatusException(409,"invalid_selection","Выбранный товар устарел, несовместим или не входит в комплект. Повторите подбор.");}
    public record Recommendation(ProductView product,String reason){}
    public record Recommendations(List<Recommendation> products,List<String> warnings,String status){}
}
