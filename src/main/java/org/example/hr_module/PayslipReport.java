package org.example.hr_module;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;

/** A single, escaped report template shared by email and browser preview. */
public final class PayslipReport {
    private PayslipReport() {}
    static String escape(Object value) {
        return Objects.toString(value,"—").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");
    }
    private static BigDecimal number(Map<String,Object> p,String key) { return new BigDecimal(Objects.toString(p.get(key),"0")); }
    private static String money(BigDecimal amount) { return new DecimalFormat("#,##0.00",DecimalFormatSymbols.getInstance(Locale.US)).format(amount); }
    private static String row(String label,String basis,BigDecimal amount) {
        return "<tr><td style='padding:10px;border-bottom:1px solid #dbe2ea'>"+escape(label)+"</td><td style='padding:10px;border-bottom:1px solid #dbe2ea;color:#64748b'>"+escape(basis)+"</td><td style='padding:10px;border-bottom:1px solid #dbe2ea;text-align:right'>"+money(amount)+"</td></tr>";
    }
    public static String html(Map<String,Object> p) {
        String month=Objects.toString(p.get("pay_month"),"").substring(0,7);
        String method=Objects.toString(p.get("method"),"");
        BigDecimal overtime=number(p,"overtime_hours").multiply(number(p,"overtime_rate"));
        BigDecimal base=number(p,"gross_pay").subtract(overtime).subtract(number(p,"allowance")).subtract(number(p,"bonus"));
        String basis=switch(method) {
            case "MONTHLY" -> money(number(p,"base_rate"))+" × "+p.get("units")+" paid days / "+p.get("standard_days")+" standard days";
            case "HOURLY" -> money(number(p,"base_rate"))+" × "+p.get("units")+" hours";
            default -> money(number(p,"base_rate"))+" × "+p.get("units")+" task units";
        };
        return "<!doctype html><html lang='en'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Salary statement</title></head>"
            +"<body style='margin:0;padding:20px;background:#edf1f5;font-family:Arial,sans-serif;color:#172b4d;font-size:13px'>"
            +"<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='max-width:760px;margin:auto;background:white;border:1px solid #dbe2ea'><tr><td style='padding:28px'>"
            +"<table role='presentation' width='100%'><tr><td style='font-size:19px;font-weight:bold'>ERP ENTERPRISE<br><span style='font-size:11px;font-weight:normal;color:#64748b'>HUMAN RESOURCES · CONFIDENTIAL</span></td><td style='text-align:right'>Statement #"+escape(p.get("id"))+"<br>Period: "+escape(month)+"</td></tr></table>"
            +"<h1 style='text-align:center;font-size:24px;letter-spacing:2px;margin:30px 0 6px'>SALARY STATEMENT</h1><p style='text-align:center;color:#64748b;margin:0 0 26px'>Payroll period "+escape(month)+" · Currency: VND</p>"
            +"<table role='presentation' width='100%' style='background:#f5f7fa;border:1px solid #dbe2ea;padding:12px;line-height:1.9'><tr><td><strong>Employee:</strong> "+escape(p.get("employee_name"))+"</td><td><strong>Account:</strong> "+escape(p.get("employee_username"))+"</td></tr><tr><td><strong>Department:</strong> "+escape(p.get("department"))+"</td><td><strong>Team:</strong> "+escape(p.get("team"))+"</td></tr><tr><td colspan='2'><strong>Salary method:</strong> "+escape(method)+"</td></tr></table>"
            +"<h2 style='font-size:14px;margin-top:25px'>01 / EARNINGS</h2><table width='100%' cellspacing='0' style='border:1px solid #dbe2ea;border-collapse:collapse'><thead><tr style='background:#172b4d;color:white'><th style='text-align:left;padding:10px'>Description</th><th style='text-align:left;padding:10px'>Calculation basis</th><th style='text-align:right;padding:10px'>Amount (VND)</th></tr></thead><tbody>"
            +row("Base pay",basis,base)+row("Overtime",p.get("overtime_hours")+" hours × "+money(number(p,"overtime_rate")),overtime)+row("Allowance","",number(p,"allowance"))+row("Bonus","",number(p,"bonus"))+row("TOTAL GROSS PAY","",number(p,"gross_pay"))+"</tbody></table>"
            +"<h2 style='font-size:14px;margin-top:25px'>02 / DEDUCTIONS</h2><table width='100%' cellspacing='0' style='border:1px solid #dbe2ea;border-collapse:collapse'>"+row("Total deductions","As entered in payroll",number(p,"deductions"))+"</table>"
            +"<table width='100%' style='margin-top:22px;background:#e8eefb;border:1px solid #b9c8e7' cellpadding='16'><tr><td style='font-weight:bold'>NET PAY · VND</td><td style='text-align:right;font-size:24px;font-weight:bold'>"+money(number(p,"net_pay"))+"</td></tr></table>"
            +"<p style='margin-top:22px'><strong>Notes</strong></p><div style='white-space:pre-wrap;overflow-wrap:anywhere;color:#475569'>"+escape(p.get("notes"))+"</div>"
            +"<p style='border-top:1px solid #dbe2ea;padding-top:16px;margin-top:32px;font-size:11px;color:#64748b'>This statement summarizes the recorded payroll amounts and does not confirm payment. Please contact your administrator for any correction.<br>ERP Enterprise · Personal and confidential</p>"
            +"</td></tr></table></body></html>";
    }
}
