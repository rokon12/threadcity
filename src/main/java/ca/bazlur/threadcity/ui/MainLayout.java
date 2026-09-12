package ca.bazlur.threadcity.ui;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;

public class MainLayout extends AppLayout {

    public MainLayout() {
        addClassName("main-layout");
        setPrimarySection(Section.DRAWER);
        buildNavbar();
        buildDrawer();
    }

    private void buildNavbar() {
        DrawerToggle drawerToggle = new DrawerToggle();
        drawerToggle.setAriaLabel("Toggle navigation");

        Div mark = new Div("TC");
        mark.addClassName("brand-mark");
        H2 name = new H2("ThreadCity");
        name.addClassName("shell-title");
        Span mode = new Span("LIVE · IN-MEMORY");
        mode.addClassName("shell-mode");

        HorizontalLayout navbar = new HorizontalLayout(drawerToggle, mark, name, mode);
        navbar.addClassName("shell-navbar");
        navbar.setAlignItems(HorizontalLayout.Alignment.CENTER);
        navbar.setWidthFull();
        navbar.expand(name);
        addToNavbar(navbar);
    }

    private void buildDrawer() {
        Span eyebrow = new Span("JVM INCIDENT WORKBENCH");
        eyebrow.addClassName("drawer-eyebrow");
        H2 title = new H2("Traffic control for Java threads");
        ParagraphCopy description = new ParagraphCopy(
                "Parse, replay, compare, investigate, and explain one production snapshot without leaving Java.");

        SideNav navigation = new SideNav("Workbench");
        navigation.addItem(
                new SideNavItem("Incident workbench", MainView.class, VaadinIcon.DASHBOARD.create()),
                new SideNavItem("Example thread dump", "/examples/jstack.txt", VaadinIcon.DOWNLOAD.create()));
        navigation.getItems().getLast().setOpenInNewBrowserTab(true);

        Div componentProof = new Div();
        componentProof.addClassName("component-proof");
        componentProof.add(new Span("POWERED BY VAADIN FLOW"), new Span(
                "AppLayout · SideNav · Tabs · Grid · Master-Detail · Split Layout · Slider · Messages · Push"));

        VerticalLayout drawer = new VerticalLayout(eyebrow, title, description, navigation, componentProof);
        drawer.addClassName("shell-drawer");
        drawer.setPadding(false);
        drawer.setSpacing(false);
        addToDrawer(drawer);
    }

    private static final class ParagraphCopy extends Span {
        private ParagraphCopy(String text) {
            super(text);
            addClassName("drawer-copy");
        }
    }
}
