-- Standard (S) VAT rates of Morocco in force per period, loaded at the founder's request (2026-10-05).
-- Source: Grant Thornton Maroc notes on the finance laws 2021 to 2026 (secondary source; the DGI site, the
-- primary source, was unavailable). The check is on the set of rates in force on a date, not per product.
-- Where the notes are silent (operations still at 7 % or 14 % in 2024-2025), the history stays permissive and
-- the reference says "à confirmer": backdated invoices are validated by an administrator anyway.
-- TODO(DGI-SPEC): confirm against CGI art. 99 (editions 2024, 2025, 2026) and the circular notes.
INSERT INTO vat_rate (id, country_code, category_code, rate_percent, valid_from, valid_to, legal_reference, created_at)
VALUES
    -- Before the 2024 reform: the four rates of CGI art. 99 (notes LF 2021 to LF 2023 reclassify operations only).
    ('6b0c0d10-0001-4c00-9000-000000000001', 'MA', 'S',  7.00, DATE '2021-01-01', DATE '2023-12-31', 'CGI art. 99 (taux réduit 7 %), notes GT LF 2021-2023', now()),
    ('6b0c0d10-0001-4c00-9000-000000000002', 'MA', 'S', 10.00, DATE '2021-01-01', DATE '2023-12-31', 'CGI art. 99 (taux réduit 10 %), notes GT LF 2021-2023', now()),
    ('6b0c0d10-0001-4c00-9000-000000000003', 'MA', 'S', 14.00, DATE '2021-01-01', DATE '2023-12-31', 'CGI art. 99 (taux réduit 14 %), notes GT LF 2021-2023', now()),
    ('6b0c0d10-0001-4c00-9000-000000000004', 'MA', 'S', 20.00, DATE '2021-01-01', DATE '2023-12-31', 'CGI art. 99 (taux normal 20 %), notes GT LF 2021-2023', now()),
    -- 2024: first step of the LF 2024 schedule.
    ('6b0c0d10-0001-4c00-9000-000000000011', 'MA', 'S',  7.00, DATE '2024-01-01', DATE '2024-12-31', 'Taux antérieur, opérations non visées par la LF 2024 : à confirmer', now()),
    ('6b0c0d10-0001-4c00-9000-000000000012', 'MA', 'S',  8.00, DATE '2024-01-01', DATE '2024-12-31', 'LF 2024 : sucre raffiné 8 % (note GT LF 2024)', now()),
    ('6b0c0d10-0001-4c00-9000-000000000013', 'MA', 'S', 10.00, DATE '2024-01-01', DATE '2024-12-31', 'CGI art. 99 taux réduit ; LF 2024 : eau hors usage domestique, voiture économique (note GT LF 2024)', now()),
    ('6b0c0d10-0001-4c00-9000-000000000014', 'MA', 'S', 11.00, DATE '2024-01-01', DATE '2024-12-31', 'LF 2024 : location de compteurs d''électricité 11 % (note GT LF 2024)', now()),
    ('6b0c0d10-0001-4c00-9000-000000000015', 'MA', 'S', 12.00, DATE '2024-01-01', DATE '2024-12-31', 'LF 2024 : électricité renouvelable, démarcheurs et courtiers d''assurance 12 % (note GT LF 2024)', now()),
    ('6b0c0d10-0001-4c00-9000-000000000016', 'MA', 'S', 13.00, DATE '2024-01-01', DATE '2024-12-31', 'LF 2024 : transport de voyageurs et de marchandises 13 % (note GT LF 2024)', now()),
    ('6b0c0d10-0001-4c00-9000-000000000017', 'MA', 'S', 14.00, DATE '2024-01-01', DATE '2024-12-31', 'Taux antérieur, opérations non visées par la LF 2024 : à confirmer', now()),
    ('6b0c0d10-0001-4c00-9000-000000000018', 'MA', 'S', 16.00, DATE '2024-01-01', DATE '2024-12-31', 'LF 2024 : électricité 16 % (note GT LF 2024)', now()),
    ('6b0c0d10-0001-4c00-9000-000000000019', 'MA', 'S', 20.00, DATE '2024-01-01', DATE '2024-12-31', 'CGI art. 99 taux normal 20 %', now()),
    -- 2025: second step of the LF 2024 schedule (the LF 2025 note changes no rate; dry yeast moves to 20 %).
    ('6b0c0d10-0001-4c00-9000-000000000021', 'MA', 'S',  7.00, DATE '2025-01-01', DATE '2025-12-31', 'Taux antérieur, opérations non visées par la LF 2024 : à confirmer', now()),
    ('6b0c0d10-0001-4c00-9000-000000000022', 'MA', 'S',  9.00, DATE '2025-01-01', DATE '2025-12-31', 'LF 2024 : sucre raffiné 9 % (note GT LF 2024)', now()),
    ('6b0c0d10-0001-4c00-9000-000000000023', 'MA', 'S', 10.00, DATE '2025-01-01', DATE '2025-12-31', 'CGI art. 99 taux réduit ; LF 2024 : électricité renouvelable, courtiers d''assurance 10 % (note GT LF 2024)', now()),
    ('6b0c0d10-0001-4c00-9000-000000000024', 'MA', 'S', 12.00, DATE '2025-01-01', DATE '2025-12-31', 'LF 2024 : transport de voyageurs et de marchandises 12 % (note GT LF 2024)', now()),
    ('6b0c0d10-0001-4c00-9000-000000000025', 'MA', 'S', 14.00, DATE '2025-01-01', DATE '2025-12-31', 'Taux antérieur, opérations non visées par la LF 2024 : à confirmer', now()),
    ('6b0c0d10-0001-4c00-9000-000000000026', 'MA', 'S', 15.00, DATE '2025-01-01', DATE '2025-12-31', 'LF 2024 : location de compteurs d''électricité 15 % (note GT LF 2024)', now()),
    ('6b0c0d10-0001-4c00-9000-000000000027', 'MA', 'S', 18.00, DATE '2025-01-01', DATE '2025-12-31', 'LF 2024 : électricité 18 % (note GT LF 2024)', now()),
    ('6b0c0d10-0001-4c00-9000-000000000028', 'MA', 'S', 20.00, DATE '2025-01-01', DATE '2025-12-31', 'CGI art. 99 taux normal 20 % ; LF 2025 : levures sèches 20 % (note GT LF 2025)', now()),
    -- From 2026: end of the LF 2024 schedule, two rates (the LF 2026 note changes no rate).
    ('6b0c0d10-0001-4c00-9000-000000000031', 'MA', 'S', 10.00, DATE '2026-01-01', NULL, 'CGI art. 99 taux réduit 10 % : fin du calendrier LF 2024 (notes GT LF 2024 et LF 2026)', now()),
    ('6b0c0d10-0001-4c00-9000-000000000032', 'MA', 'S', 20.00, DATE '2026-01-01', NULL, 'CGI art. 99 taux normal 20 % : fin du calendrier LF 2024 (notes GT LF 2024 et LF 2026)', now());
