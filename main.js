(() => {
  const toggle = document.querySelector(".nav-toggle");
  const nav = document.querySelector(".site-header nav");
  if (toggle && nav) {
    toggle.addEventListener("click", () => {
      const open = toggle.getAttribute("aria-expanded") === "true";
      toggle.setAttribute("aria-expanded", String(!open));
      nav.classList.toggle("is-open", !open);
    });
    nav.querySelectorAll("a").forEach((a) => {
      a.addEventListener("click", () => {
        toggle.setAttribute("aria-expanded", "false");
        nav.classList.remove("is-open");
      });
    });
    const navMedia = window.matchMedia("(min-width: 901px)");
    const closeNav = () => {
      toggle.setAttribute("aria-expanded", "false");
      nav.classList.remove("is-open");
    };
    navMedia.addEventListener("change", (e) => {
      if (e.matches) closeNav();
    });
  }

  document.querySelectorAll("[data-copy]").forEach((block) => {
    const btn = block.querySelector(".copy-btn");
    const code = block.querySelector("code");
    if (!btn || !code) return;
    btn.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(code.textContent || "");
        btn.textContent = "Copied";
        btn.classList.add("copied");
        setTimeout(() => {
          btn.textContent = "Copy";
          btn.classList.remove("copied");
        }, 1600);
      } catch {
        btn.textContent = "Select & copy";
      }
    });
  });
})();
