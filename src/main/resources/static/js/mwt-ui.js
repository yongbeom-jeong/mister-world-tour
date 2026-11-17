/* 화면 공통 입력 부품 (모든 화면에서 불러온다)
 *
 * 1. 선택 상자: 브라우저 기본 <select> 목록 대신 디자인된 목록(Bootstrap dropdown)을 보여 준다.
 *    실제 값은 원래 select 가 그대로 가진다 → 폼 전송·서버 검증·기존 화면 스크립트(id_theme 등)는 바뀌지 않는다.
 *    다른 스크립트가 옵션·disabled 를 바꾸면 MutationObserver 로 다시 그린다.
 *    기본 select 를 그대로 쓰려면 data-native 속성을 붙인다.
 * 2. 날짜: <input type="date"> 를 디자인된 달력(flatpickr, 한국어)으로. flatpickr 를 불러온 화면에서만 동작한다.
 */
(() => {
  "use strict";

  // ---------------------------------------------------------------- 선택 상자
  let uid = 0;

  function enhanceSelect(select) {
    if (select.dataset.enhanced || select.multiple || "native" in select.dataset) return;
    select.dataset.enhanced = "1";

    const wrap = document.createElement("div");
    wrap.className = "mwt-select dropdown";
    if (select.classList.contains("form-select-sm")) wrap.classList.add("mwt-select-sm");
    // 원래 select 에 준 폭 제한(style)은 감싸는 요소로 옮긴다
    if (select.style.maxWidth) { wrap.style.maxWidth = select.style.maxWidth; select.style.maxWidth = ""; }

    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "mwt-select-btn";
    btn.id = (select.id || "mwt-select-" + ++uid) + "_ui";
    btn.setAttribute("data-bs-toggle", "dropdown");
    btn.setAttribute("aria-haspopup", "listbox");
    btn.setAttribute("aria-expanded", "false");
    const text = document.createElement("span");
    text.className = "mwt-select-text";
    btn.append(text);

    const menu = document.createElement("div");
    menu.className = "dropdown-menu mwt-select-menu";
    menu.setAttribute("role", "listbox");

    select.parentNode.insertBefore(wrap, select);
    wrap.append(select, btn, menu);
    select.classList.add("mwt-select-native");
    select.tabIndex = -1;
    select.setAttribute("aria-hidden", "true");
    // 라벨은 화면의 버튼을 가리키게 한다 (라벨을 누르면 버튼에 초점)
    if (select.id) {
      document.querySelectorAll(`label[for="${CSS.escape(select.id)}"]`).forEach(l => { l.htmlFor = btn.id; });
      if (!select.labels?.length && select.getAttribute("aria-label")) btn.setAttribute("aria-label", select.getAttribute("aria-label"));
    }

    function render() {
      const options = [...select.options];
      const current = select.selectedOptions[0] || options[0];
      text.textContent = current ? current.textContent : "";
      btn.classList.toggle("is-placeholder", !select.value);
      btn.classList.toggle("is-invalid", select.classList.contains("is-invalid"));
      btn.disabled = select.disabled;
      menu.replaceChildren(...options.map(opt => {
        const item = document.createElement("button");
        item.type = "button";
        item.className = "dropdown-item mwt-select-item";
        item.setAttribute("role", "option");
        item.setAttribute("aria-selected", opt === current ? "true" : "false");
        item.disabled = opt.disabled;
        item.textContent = opt.textContent;
        if (opt === current) item.classList.add("active");
        item.addEventListener("click", () => {
          if (select.value !== opt.value) {
            select.value = opt.value;
            select.dispatchEvent(new Event("change", { bubbles: true }));   // 기존 화면 스크립트에 알림
          }
          render();
          btn.focus();
        });
        return item;
      }));
    }

    let queued = false;
    const rerender = () => { if (!queued) { queued = true; queueMicrotask(() => { queued = false; render(); }); } };
    new MutationObserver(rerender).observe(select, { childList: true, subtree: true, attributes: true, characterData: true });
    select.addEventListener("change", rerender);
    // 다른 스크립트가 select.value 만 바꾼 경우도 열 때 맞춘다
    btn.addEventListener("show.bs.dropdown", render);
    btn.addEventListener("shown.bs.dropdown", () => menu.querySelector(".active")?.focus());
    render();
  }

  // ---------------------------------------------------------------- 날짜
  function enhanceDate(input) {
    if (!window.flatpickr || input.dataset.enhanced || "native" in input.dataset) return;
    input.dataset.enhanced = "1";
    window.flatpickr(input, {
      locale: window.flatpickr.l10ns?.ko || "default",
      dateFormat: "Y-m-d",          // 서버로 보내는 값은 그대로
      altInput: true,
      altFormat: "Y년 n월 j일",       // 화면에 보이는 형식
      monthSelectorType: "static",   // 월 선택도 기본 목록 대신 화살표로 넘긴다
      minDate: input.min || null,
      maxDate: input.max || null,
      disableMobile: true,
      altInputClass: input.className + " mwt-date",   // 원래 입력칸 스타일을 이어받는다
      onReady: (_, __, fp) => {
        fp.altInput.classList.toggle("is-invalid", input.classList.contains("is-invalid"));
        fp.altInput.placeholder = "날짜를 고르세요";
        if (input.id) document.querySelectorAll(`label[for="${CSS.escape(input.id)}"]`)
          .forEach(l => { fp.altInput.id = input.id + "_ui"; l.htmlFor = fp.altInput.id; });
      },
    });
  }

  function init(root = document) {
    root.querySelectorAll("select.form-select").forEach(enhanceSelect);
    root.querySelectorAll('input[type="date"]').forEach(enhanceDate);
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", () => init());
  else init();
  window.mwtUI = { init };
})();
