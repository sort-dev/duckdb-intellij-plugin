UNPIVOT monthly_sales
ON jan, feb, mar
INTO NAME month VALUE sales;

SELECT * FROM monthly_sales
UNPIVOT (sales FOR month IN (jan, feb, mar));
