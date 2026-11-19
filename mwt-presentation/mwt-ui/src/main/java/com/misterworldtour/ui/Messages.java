package com.misterworldtour.ui;

import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** 화면 위쪽 안내 문구. redirect 뒤에는 {@link #flash}, 지금 화면에는 {@link #now}. */
public final class Messages {

    public static final String ATTR = "messages";

    public enum Level { SUCCESS, INFO, WARNING, ERROR }

    public record Message(Level level, String text) implements Serializable {
        public String css() {
            return level == Level.ERROR ? "danger" : level.name().toLowerCase();
        }

        public String icon() {
            return switch (level) {
                case SUCCESS -> "bi-check-circle-fill";
                case ERROR -> "bi-exclamation-octagon-fill";
                case WARNING -> "bi-exclamation-triangle-fill";
                case INFO -> "bi-info-circle-fill";
            };
        }
    }

    private Messages() {
    }

    @SuppressWarnings("unchecked")
    public static void flash(RedirectAttributes attrs, Level level, String text) {
        List<Message> list = (List<Message>) attrs.getFlashAttributes().get(ATTR);
        if (list == null) {
            list = new ArrayList<>();
            attrs.addFlashAttribute(ATTR, list);
        }
        list.add(new Message(level, text));
    }

    @SuppressWarnings("unchecked")
    public static void now(Model model, Level level, String text) {
        List<Message> list = (List<Message>) model.getAttribute(ATTR);
        if (list == null) {
            list = new ArrayList<>();
            model.addAttribute(ATTR, list);
        }
        list.add(new Message(level, text));
    }
}
