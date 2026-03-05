-- Expand test_type enum to include ssl, dns, tcp, udp
ALTER TABLE synthetic_results MODIFY COLUMN test_type Enum8(
    'api' = 1,
    'browser' = 2,
    'multistep' = 3,
    'ssl' = 4,
    'dns' = 5,
    'tcp' = 6,
    'udp' = 7
);
