package ca.bazlur.threadcity.ui.component;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns the tab-to-page relationship for the analysis workbench. */
public final class WorkbenchNavigator extends Div {

    private final Tabs tabs = new Tabs();
    private final Map<Tab, Component> pages = new LinkedHashMap<>();

    public WorkbenchNavigator(List<Page> definitions) {
        Div pageContainer = new Div();
        pageContainer.addClassName("workbench-pages");
        definitions.forEach(definition -> {
            pages.put(definition.tab(), definition.content());
            tabs.add(definition.tab());
            definition.content().addClassName("workbench-page");
            definition.content().setVisible(pages.size() == 1);
            pageContainer.add(definition.content());
        });
        tabs.addClassName("workbench-tabs");
        tabs.setWidthFull();
        tabs.addSelectedChangeListener(event -> show(event.getSelectedTab()));
        add(tabs, pageContainer);
    }

    public void select(Tab tab) {
        if (!pages.containsKey(tab)) {
            throw new IllegalArgumentException("Tab does not belong to this workbench");
        }
        tabs.setSelectedTab(tab);
        show(tab);
    }

    private void show(Tab selected) {
        pages.forEach((tab, page) -> page.setVisible(tab == selected));
    }

    public record Page(Tab tab, Component content) {
    }
}
