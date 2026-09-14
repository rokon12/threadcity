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
import com.vaadin.flow.shared.Registration;

public class MainLayout extends AppLayout {

    private static final int COMPACT_DRAWER_BREAKPOINT = 1_024;

    private Registration resizeRegistration;

    public MainLayout() {
        addClassName("main-layout");
        setPrimarySection(Section.DRAWER);
        buildNavbar();
        buildDrawer();
        registerResponsiveDrawer();
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
        SideNavItem workbench = new SideNavItem(
                "Incident workbench", MainView.class, VaadinIcon.DASHBOARD.create());
        SideNavItem threadDump = new SideNavItem(
                "Example thread dump", "/examples/jstack.txt", VaadinIcon.DOWNLOAD.create());
        SideNavItem jfrRecording = new SideNavItem(
                "Example JFR recording", "/examples/threadcity-demo.jfr", VaadinIcon.CLOCK.create());
        threadDump.setOpenInNewBrowserTab(true);
        jfrRecording.setOpenInNewBrowserTab(true);
        navigation.addItem(workbench, threadDump, jfrRecording);

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

    private void registerResponsiveDrawer() {
        addAttachListener(event -> {
            var page = event.getUI().getPage();
            page.retrieveExtendedClientDetails(
                    details -> closeDrawerOnCompactViewport(details.getWindowInnerWidth()));
            resizeRegistration = page.addBrowserWindowResizeListener(
                    resize -> closeDrawerOnCompactViewport(resize.getWidth()));
        });
        addDetachListener(event -> {
            if (resizeRegistration != null) {
                resizeRegistration.remove();
                resizeRegistration = null;
            }
        });
    }

    private void closeDrawerOnCompactViewport(int width) {
        if (width <= COMPACT_DRAWER_BREAKPOINT) {
            setDrawerOpened(false);
        }
    }

    private static final class ParagraphCopy extends Span {
        private ParagraphCopy(String text) {
            super(text);
            addClassName("drawer-copy");
        }
    }
}
