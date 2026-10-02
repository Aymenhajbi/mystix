import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Mystix",
  description: "Intégration EDI, facturation électronique et clearance DGI",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="fr" dir="ltr">
      <body>{children}</body>
    </html>
  );
}
