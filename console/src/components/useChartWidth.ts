import { useCallback, useState } from "react";

/** SVG souřadnice sledují skutečný sloupec, aby osa na telefonu nezmenšila text na 5 px. */
export function useChartWidth() {
  const [width, setWidth] = useState(640);
  const ref = useCallback((node: HTMLElement | null) => {
    if (!node) return;
    const observer = new ResizeObserver((entries) => {
      const value = entries[0]?.contentRect.width;
      if (value) setWidth(Math.max(280, Math.min(900, Math.round(value))));
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, []);
  return { ref, width };
}
