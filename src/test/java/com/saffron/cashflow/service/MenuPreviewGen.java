package com.saffron.cashflow.service;
import com.saffron.cashflow.domain.MenuCategory; import com.saffron.cashflow.domain.MenuItem;
import org.junit.jupiter.api.Test; import java.io.IOException; import java.math.BigDecimal; import java.nio.file.*; import java.util.*;
class MenuPreviewGen {
    @Test void render() throws IOException {
        Path up=Files.createTempDirectory("mp"); Files.createDirectories(up.resolve("menu"));
        Files.copy(Path.of("/tmp/dish.webp"), up.resolve("menu/dish.webp"));
        List<MenuCategory> cats=List.of(cat("c1","Przystawki",10));
        Map<String,List<MenuItem>> it=new LinkedHashMap<>(); it.put("c1",List.of(img("Dolma","Liście winogron",26)));
        byte[] pdf=new MenuPrintService(new StubSvc(cats,it),new StubFs(up)).buildMenu("photolist","Saffron","",true,"pl");
        Files.write(Path.of("/private/tmp/claude-501/-Users-ihakhverdiyev-Desktop-saffron-app/5572d5a5-663d-4159-88a9-291f91aab7b1/scratchpad/menu-pl.pdf"),pdf);
        System.out.println("WROTE");
    }
    static MenuCategory cat(String id,String n,int s){MenuCategory c=new MenuCategory();c.setId(id);c.setName(n);c.setSortOrder(s);c.setActive(true);return c;}
    MenuItem img(String n,String d,int p){MenuItem i=new MenuItem();i.setId(n);i.setCategoryId("c1");i.setName(n);i.setLongDescription(d);i.setSellPrice(new BigDecimal(p));i.setVatRatePct(new BigDecimal("8"));i.setActive(true);i.setImagePath("menu/dish.webp");return i;}
    static final class StubFs extends FileStorageService{final Path d;StubFs(Path x)throws IOException{super(x.toString(),null,null);d=x;}@Override public Path getUploadDir(){return d;}}
    static final class StubSvc extends MenuService{final List<MenuCategory> c;final Map<String,List<MenuItem>> i;StubSvc(List<MenuCategory> c,Map<String,List<MenuItem>> i){super(null,null,null);this.c=c;this.i=i;}@Override public List<MenuCategory> activeCategoriesInOrder(){return c;}@Override public List<MenuItem> activeItemsForCategory(String id){return i.getOrDefault(id,List.of());}}
}
