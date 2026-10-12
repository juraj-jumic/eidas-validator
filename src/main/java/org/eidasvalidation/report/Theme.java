package org.eidasvalidation.report;

import org.eidasvalidation.i18n.Messages;

/**
 * The one visual system shared by the web pages and the standalone HTML report: colour tokens
 * for dark and light schemes, type, cards, badges and the status banner. Inline only, no
 * external assets, no scripts, so a saved report is self-contained and the server's CSP stays
 * {@code default-src 'none'}.
 */
public final class Theme {

    private Theme() {
    }

    /** Document head with the stylesheet; the caller closes {@code </head>}. */
    public static String head(Messages m, String title) {
        return "<!doctype html>\n<html lang=\"" + HtmlReport.esc(m.tag()) + "\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<meta name=\"color-scheme\" content=\"dark light\">\n"
                + "<title>" + HtmlReport.esc(title) + "</title>\n<style>\n" + CSS + "</style>\n";
    }

    /** Top bar with brand and tagline. */
    public static String topbar(Messages m) {
        return "<header class=\"topbar\">\n<div class=\"brand\">" + ICON_SHIELD + "<span>"
                + HtmlReport.esc(m.get("ui.brand")) + "</span></div>\n<p class=\"tagline\">"
                + HtmlReport.esc(m.get("ui.tagline")) + "</p>\n</header>\n";
    }

    public static String footer(Messages m, String engine) {
        return "<footer class=\"foot\"><p>" + HtmlReport.esc(m.format("html.footer", engine)) + "</p></footer>\n";
    }

    public static String icon(String verdictClass) {
        return switch (verdictClass) {
            case "valid" -> ICON_CHECK;
            case "invalid" -> ICON_CROSS;
            case "indeterminate" -> ICON_WARN;
            default -> ICON_DOC;
        };
    }

    public static final String ICON_SHIELD = "<svg class=\"ic\" viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"M12 2 4 5v6c0 5 3.4 9.3 8 11 4.6-1.7 8-6 8-11V5z\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linejoin=\"round\"/><path d=\"m8.5 12 2.3 2.3L15.5 9.6\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>";
    public static final String ICON_CHECK = "<svg class=\"ic\" viewBox=\"0 0 24 24\" aria-hidden=\"true\"><circle cx=\"12\" cy=\"12\" r=\"9.5\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\"/><path d=\"m7.5 12.2 3 3 6-6.5\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>";
    public static final String ICON_CROSS = "<svg class=\"ic\" viewBox=\"0 0 24 24\" aria-hidden=\"true\"><circle cx=\"12\" cy=\"12\" r=\"9.5\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\"/><path d=\"m8.5 8.5 7 7m0-7-7 7\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\"/></svg>";
    public static final String ICON_WARN = "<svg class=\"ic\" viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"M12 3.5 21 19.5H3z\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linejoin=\"round\"/><path d=\"M12 9v4.5\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\"/><circle cx=\"12\" cy=\"16.5\" r=\"1\" fill=\"currentColor\"/></svg>";
    public static final String ICON_DOC = "<svg class=\"ic\" viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"M7 3h7l4 4v14H7z\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linejoin=\"round\"/><path d=\"M14 3v4h4M9.5 12h5m-5 3.5h5\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\"/></svg>";
    public static final String ICON_UPLOAD = "<svg class=\"ic big\" viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"M7 3h7l4 4v14H7z\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linejoin=\"round\"/><path d=\"M14 3v4h4M12 17v-6m-2.5 2.5L12 11l2.5 2.5\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>";

    static final String CSS = """
            :root {
              --bg: #0b1220; --panel: #121b2e; --panel-2: #172238; --line: #223049; --line-strong: #2f4063;
              --fg: #e7edf7; --muted: #93a0b8; --faint: #6b7a94;
              --accent: #4f8df7; --accent-soft: rgba(79,141,247,.14);
              --valid: #34d399; --valid-soft: rgba(52,211,153,.12); --valid-line: rgba(52,211,153,.35);
              --invalid: #f87171; --invalid-soft: rgba(248,113,113,.12); --invalid-line: rgba(248,113,113,.35);
              --indet: #fbbf24; --indet-soft: rgba(251,191,36,.12); --indet-line: rgba(251,191,36,.35);
              --none: #93a0b8; --none-soft: rgba(147,160,184,.12); --none-line: rgba(147,160,184,.35);
              --radius: 14px; --shadow: 0 1px 0 rgba(255,255,255,.03) inset, 0 10px 30px rgba(0,0,0,.25);
              --mono: ui-monospace, "JetBrains Mono", Consolas, "SFMono-Regular", Menlo, monospace;
            }
            @media (prefers-color-scheme: light) { :root {
              --bg: #f3f5f9; --panel: #ffffff; --panel-2: #f6f8fc; --line: #dde3ee; --line-strong: #c7d0e0;
              --fg: #0f172a; --muted: #4b5a73; --faint: #7b879c;
              --accent: #2563eb; --accent-soft: rgba(37,99,235,.10);
              --valid: #15803d; --valid-soft: rgba(21,128,61,.10); --valid-line: rgba(21,128,61,.30);
              --invalid: #b91c1c; --invalid-soft: rgba(185,28,28,.08); --invalid-line: rgba(185,28,28,.30);
              --indet: #b45309; --indet-soft: rgba(180,83,9,.10); --indet-line: rgba(180,83,9,.30);
              --none: #4b5a73; --none-soft: rgba(75,90,115,.08); --none-line: rgba(75,90,115,.30);
              --shadow: 0 1px 2px rgba(15,23,42,.06), 0 8px 24px rgba(15,23,42,.06);
            } }
            * { box-sizing: border-box; }
            html { background: var(--bg); color: var(--fg); font: 15px/1.55 ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, Inter, sans-serif; -webkit-font-smoothing: antialiased; }
            body { margin: 0; }
            a { color: var(--accent); }
            .topbar { display: flex; align-items: baseline; gap: 1rem; padding: 1rem 1.5rem; border-bottom: 1px solid var(--line); background: var(--panel); }
            .brand { display: inline-flex; align-items: center; gap: .55rem; font-weight: 700; letter-spacing: .01em; color: var(--fg); }
            .brand .ic { width: 22px; height: 22px; color: var(--accent); }
            .tagline { margin: 0; color: var(--muted); font-size: .9rem; }
            main { max-width: 860px; margin: 0 auto; padding: 1.75rem 1rem 3rem; }
            h1 { font-size: 1.5rem; margin: 0 0 .25rem; letter-spacing: -.01em; }
            .lead { color: var(--muted); margin: 0 0 1.25rem; }
            .card { background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius); box-shadow: var(--shadow); }
            .card + .card { margin-top: 1rem; }
            .ic { width: 20px; height: 20px; flex: none; }
            .ic.big { width: 36px; height: 36px; }
            /* upload */
            .drop { display: block; border: 1.5px dashed var(--line-strong); border-radius: 12px; padding: 2rem 1rem; text-align: center; color: var(--muted); cursor: pointer; background: var(--panel-2); }
            .drop:hover, .drop:focus-within { border-color: var(--accent); color: var(--fg); }
            .drop .ic { color: var(--accent); margin-bottom: .5rem; }
            .drop strong { display: block; color: var(--fg); font-weight: 600; }
            .drop small { color: var(--faint); }
            .drop input[type=file] { display: block; margin: 1rem auto 0; color: var(--muted); font: inherit; max-width: 100%; }
            .row { display: flex; flex-wrap: wrap; gap: .75rem 1rem; align-items: center; margin-top: 1rem; }
            .check { display: inline-flex; align-items: center; gap: .5rem; color: var(--muted); }
            .check input { width: 1.05rem; height: 1.05rem; accent-color: var(--accent); }
            .btn { display: inline-flex; align-items: center; gap: .5rem; font: inherit; font-weight: 600; color: #fff; background: var(--accent); border: 0; border-radius: 10px; padding: .6rem 1.1rem; cursor: pointer; text-decoration: none; }
            .btn.secondary { color: var(--fg); background: var(--panel-2); border: 1px solid var(--line-strong); }
            .btn:focus-visible, .drop input:focus-visible, .check input:focus-visible, summary:focus-visible { outline: 3px solid var(--indet); outline-offset: 2px; }
            .pad { padding: 1.25rem; }
            .trustline { display: flex; gap: .6rem; align-items: flex-start; color: var(--muted); font-size: .9rem; border-top: 1px solid var(--line); padding: 1rem 1.25rem; }
            .trustline .ic { color: var(--accent); margin-top: .1rem; }
            /* banner */
            .banner { display: flex; gap: 1rem; align-items: flex-start; padding: 1.1rem 1.25rem; border-radius: var(--radius); border: 1px solid var(--none-line); background: var(--none-soft); }
            .banner .ic { width: 28px; height: 28px; margin-top: .1rem; }
            .banner h2 { margin: 0; font-size: 1.1rem; }
            .banner p { margin: .1rem 0 0; color: var(--muted); font-size: .92rem; }
            .banner .meta { margin-left: auto; text-align: right; font-size: .8rem; color: var(--muted); line-height: 1.5; }
            .banner .meta b { display: block; font-weight: 600; color: var(--fg); word-break: break-all; }
            .banner .meta span { letter-spacing: .08em; font-size: .68rem; text-transform: uppercase; color: var(--faint); }
            .v-valid { border-color: var(--valid-line); background: var(--valid-soft); } .v-valid .ic, .v-valid h2 { color: var(--valid); }
            .v-invalid { border-color: var(--invalid-line); background: var(--invalid-soft); } .v-invalid .ic, .v-invalid h2 { color: var(--invalid); }
            .v-indeterminate { border-color: var(--indet-line); background: var(--indet-soft); } .v-indeterminate .ic, .v-indeterminate h2 { color: var(--indet); }
            .v-no-signature .ic, .v-no-signature h2 { color: var(--none); }
            /* signer card */
            .signer { padding: 0; overflow: hidden; }
            .signer-head { display: flex; gap: .9rem; align-items: center; padding: 1rem 1.25rem; }
            .num { width: 30px; height: 30px; border-radius: 50%; display: grid; place-items: center; font-weight: 700; font-size: .85rem; color: #fff; background: var(--accent); flex: none; }
            .signer-head h3 { margin: 0; font-size: 1.05rem; }
            .badges { display: flex; flex-wrap: wrap; gap: .4rem; margin-top: .35rem; }
            .badge { display: inline-block; font-size: .74rem; font-weight: 600; padding: .18rem .55rem; border-radius: 999px; border: 1px solid var(--line-strong); background: var(--panel-2); color: var(--muted); letter-spacing: .01em; }
            .badge.valid { color: var(--valid); border-color: var(--valid-line); background: var(--valid-soft); }
            .badge.invalid { color: var(--invalid); border-color: var(--invalid-line); background: var(--invalid-soft); }
            .badge.indeterminate { color: var(--indet); border-color: var(--indet-line); background: var(--indet-soft); }
            .badge.q { color: var(--accent); border-color: var(--accent); background: var(--accent-soft); }
            .facts { display: grid; grid-template-columns: 1fr 1fr; border-top: 1px solid var(--line); }
            .fact { padding: .9rem 1.25rem; border-bottom: 1px solid var(--line); }
            .fact:nth-child(odd) { border-right: 1px solid var(--line); }
            .fact .k { font-size: .68rem; letter-spacing: .08em; text-transform: uppercase; color: var(--faint); }
            .fact .v { font-weight: 600; margin-top: .15rem; }
            .fact .n { color: var(--muted); font-size: .82rem; margin-top: .1rem; }
            .chain { padding: .9rem 1.25rem; border-bottom: 1px solid var(--line); }
            .chain ol { list-style: none; margin: .3rem 0 0; padding: 0; display: flex; flex-wrap: wrap; gap: .25rem .4rem; align-items: center; }
            .chain li { display: inline-flex; align-items: center; gap: .4rem; font-weight: 600; }
            .chain li::before { content: ""; width: 8px; height: 8px; border-radius: 50%; background: var(--accent); }
            .chain li + li::before { background: var(--muted); }
            .chain li + li { margin-left: .2rem; }
            .chain li + li::after { content: none; }
            .chain .sep { color: var(--faint); font-weight: 400; }
            .explain { padding: 1rem 1.25rem; border-bottom: 1px solid var(--line); }
            .explain p { margin: 0 0 .5rem; }
            .explain p:last-child { margin-bottom: 0; }
            .explain .next { display: flex; gap: .6rem; align-items: flex-start; background: var(--panel-2); border: 1px solid var(--line); border-radius: 10px; padding: .7rem .9rem; margin-top: .75rem; }
            .explain .next .ic { color: var(--accent); margin-top: .15rem; }
            .explain ul { margin: .4rem 0 0 1.1rem; padding: 0; color: var(--muted); font-size: .9rem; }
            .note { color: var(--indet); font-size: .9rem; }
            details.tech { border-top: 1px solid var(--line); }
            details.tech summary { padding: .85rem 1.25rem; cursor: pointer; color: var(--muted); font-weight: 600; font-size: .92rem; list-style: none; display: flex; align-items: center; gap: .5rem; }
            details.tech summary::-webkit-details-marker { display: none; }
            details.tech summary::after { content: "▾"; margin-left: auto; color: var(--faint); }
            details.tech[open] summary::after { content: "▴"; }
            .kv { padding: .25rem 1.25rem 1rem; font-size: .85rem; }
            .kv div { display: grid; grid-template-columns: 11rem 1fr; gap: .5rem; padding: .3rem 0; border-top: 1px dashed var(--line); }
            .kv dt { color: var(--muted); }
            .kv dd { margin: 0; font-family: var(--mono); font-size: .8rem; word-break: break-all; }
            .kv ul { margin: 0; padding-left: 1rem; font-family: inherit; }
            section.plain { margin-top: 1.5rem; }
            section.plain h2 { font-size: 1rem; margin: 0 0 .5rem; color: var(--muted); letter-spacing: .02em; }
            dl.list { margin: 0; display: grid; grid-template-columns: max-content 1fr; gap: .3rem 1rem; }
            dl.list dt { color: var(--muted); } dl.list dd { margin: 0; }
            .cols { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; }
            .cols ul { margin: .25rem 0 0 1.1rem; padding: 0; color: var(--muted); font-size: .92rem; }
            .cols h3 { margin: 0; font-size: .9rem; }
            pre { overflow: auto; background: var(--panel-2); border: 1px solid var(--line); padding: .75rem; border-radius: 10px; font-size: .78rem; max-height: 28rem; font-family: var(--mono); }
            .foot { max-width: 860px; margin: 0 auto; padding: 0 1rem 2rem; color: var(--faint); font-size: .82rem; }
            .err { padding: 1.25rem; }
            @media (max-width: 640px) { .facts { grid-template-columns: 1fr; } .fact:nth-child(odd) { border-right: 0; } .banner { flex-wrap: wrap; } .banner .meta { margin-left: 0; text-align: left; } .cols { grid-template-columns: 1fr; } .kv div { grid-template-columns: 1fr; } .topbar { flex-direction: column; gap: .2rem; } }
            """;
}
