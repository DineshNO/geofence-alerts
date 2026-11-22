import type { ReactNode } from 'react';

/** Small stroke icons drawn inline (no icon font or library, so nothing is fetched). Decorative: the button names them. */
function Icon({ children }: { children: ReactNode }) {
  return (
    <svg
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.4"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {children}
    </svg>
  );
}

export function ChevronUpIcon() {
  return (
    <Icon>
      <path d="M6 15l6-6 6 6" />
    </Icon>
  );
}

export function ChevronDownIcon() {
  return (
    <Icon>
      <path d="M6 9l6 6 6-6" />
    </Icon>
  );
}

export function CloseIcon() {
  return (
    <Icon>
      <path d="M6 6l12 12M18 6L6 18" />
    </Icon>
  );
}
