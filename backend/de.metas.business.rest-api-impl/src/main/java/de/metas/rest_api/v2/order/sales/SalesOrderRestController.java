/*
 * #%L
 * de.metas.business.rest-api-impl
 * %%
 * Copyright (C) 2021 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

package de.metas.rest_api.v2.order.sales;

import com.google.common.collect.ImmutableList;
import de.metas.Profiles;
import de.metas.attachments.AttachmentEntry;
import de.metas.attachments.AttachmentEntryId;
import de.metas.attachments.AttachmentEntryService;
import de.metas.bpartner.BPartnerId;
import de.metas.bpartner.service.BPartnerQuery;
import de.metas.bpartner.service.IBPartnerDAO;
import de.metas.common.rest_api.v2.order.JsonOrderPaymentCreateRequest;
import de.metas.document.DocBaseType;
import de.metas.document.DocTypeId;
import de.metas.document.DocTypeQuery;
import de.metas.document.IDocTypeDAO;
import de.metas.gs1.GTIN;
import de.metas.logging.LogManager;
import de.metas.order.IOrderBL;
import de.metas.order.OrderFactory;
import de.metas.order.IOrderDAO;
import de.metas.organization.OrgId;
import de.metas.product.IProductBL;
import de.metas.product.IProductDAO;
import de.metas.product.ProductId;
import de.metas.quantity.Quantity;
import de.metas.rest_api.utils.JsonErrors;
import de.metas.rest_api.v2.order.JsonSalesOrder;
import de.metas.rest_api.v2.order.JsonSalesOrderAttachment;
import de.metas.rest_api.v2.order.JsonSalesOrderCreateRequest;
import de.metas.rest_api.v2.order.JsonSalesOrderLine;
import de.metas.rest_api.v2.order.JsonSalesOrderLineDetail;
import de.metas.rest_api.v2.util.JsonConverters;
import de.metas.rest_api.v2.order.sales.OrderService;
import de.metas.util.Check;
import de.metas.util.Services;
import de.metas.util.web.MetasfreshRestAPIConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.NonNull;
import org.adempiere.ad.trx.api.ITrx;
import org.adempiere.ad.trx.api.ITrxManager;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.util.lang.impl.TableRecordReference;
import org.compiere.model.I_C_Order;
import de.metas.interfaces.I_C_OrderLine;
import org.compiere.model.I_C_UOM;
import org.compiere.model.I_M_Product;
import org.compiere.util.Env;
import org.slf4j.Logger;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping(value = MetasfreshRestAPIConstants.ENDPOINT_API_V2 + "/orders/sales")
@Profile(Profiles.PROFILE_Webui)
public class SalesOrderRestController
{
	private static final Logger logger = LogManager.getLogger(SalesOrderRestController.class);
	private final OrderService orderService;
	private final AttachmentEntryService attachmentEntryService;

	public SalesOrderRestController(
			final OrderService orderService,
			final AttachmentEntryService attachmentEntryService)
	{
		this.orderService = orderService;
		this.attachmentEntryService = attachmentEntryService;
	}

	@Operation(summary = "Create new order payment")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Successfully created new order payment"),
			@ApiResponse(responseCode = "401", description = "You are not authorized to create a new order payment"),
			@ApiResponse(responseCode = "403", description = "Accessing a related resource is forbidden"),
			@ApiResponse(responseCode = "422", description = "The request body could not be processed")
	})
	@PostMapping("/payment")
	public ResponseEntity<?> createOrderPayment(@NonNull @RequestBody final JsonOrderPaymentCreateRequest request)
	{
		try
		{
			orderService.createOrderPayment(request);

			return ResponseEntity.ok().build();
		}
		catch (final Exception ex)
		{
			logger.error(ex.getMessage(), ex);

			final String adLanguage = Env.getADLanguageOrBaseLanguage();

			return ResponseEntity.unprocessableEntity()
					.body(JsonErrors.ofThrowable(ex, adLanguage));
		}
	}

	@PostMapping
	public JsonSalesOrder createOrder(@RequestBody final JsonSalesOrderCreateRequest request)
	{
		final ITrxManager trxManager = Services.get(ITrxManager.class);
		return trxManager.call(ITrx.TRXNAME_ThreadInherited, () -> createOrder0(request));
	}

	@GetMapping("/{salesOrderId}/attachments")
	public List<JsonSalesOrderAttachment> getAttachments(@PathVariable("salesOrderId") final String salesOrderIdStr)
	{
		final int salesOrderId = Integer.parseInt(salesOrderIdStr);
		final TableRecordReference salesOrderRef = TableRecordReference.of(I_C_Order.Table_Name, salesOrderId);

		return attachmentEntryService.getByReferencedRecord(salesOrderRef)
				.stream()
				.map(entry -> toSalesOrderAttachment(salesOrderId, entry))
				.collect(ImmutableList.toImmutableList());
	}

	@PostMapping("/{salesOrderId}/attachments")
	public JsonSalesOrderAttachment attachFile(
			@PathVariable("salesOrderId") final String salesOrderIdStr,
			@RequestParam("file") @NonNull final MultipartFile file)
			throws IOException
	{
		final int salesOrderId = Integer.parseInt(salesOrderIdStr);
		final TableRecordReference salesOrderRef = TableRecordReference.of(I_C_Order.Table_Name, salesOrderId);

		final String name = file.getOriginalFilename();
		final byte[] data = file.getBytes();

		final AttachmentEntry entry = attachmentEntryService.createNewAttachment(salesOrderRef, name, data);
		return toSalesOrderAttachment(salesOrderId, entry);
	}

	private JsonSalesOrder createOrder0(@RequestBody final JsonSalesOrderCreateRequest request)
	{
		final IBPartnerDAO bpartnersRepo = Services.get(IBPartnerDAO.class);

		final OrderFactory salesOrderFactory = OrderFactory.newSalesOrder();

		if (!Check.isEmpty(request.getDocTypeName(), true))
		{
			final IDocTypeDAO docTypeDAO = Services.get(IDocTypeDAO.class);
			final DocTypeQuery query = DocTypeQuery.builder()
					.docBaseType(DocBaseType.SalesOrder)
					.adClientId(Env.getAD_Client_ID())
					.name(request.getDocTypeName())
					.build();
			final DocTypeId docTypeId = docTypeDAO.getDocTypeId(query);
			salesOrderFactory.docType(docTypeId);
		}

		final BPartnerQuery query = BPartnerQuery.builder()
				.bpartnerValue(request.getShipBPartnerCode())
				.onlyOrgId(Env.getOrgId())
				.failIfNotExists(true)
				.build();

		final BPartnerId shipBPartnerId = bpartnersRepo
				.retrieveBPartnerIdBy(query)
				.get()/* bc failIfNotExists(true) */;

		salesOrderFactory.shipBPartner(shipBPartnerId);

		salesOrderFactory.datePromised(request.getDatePromised());

		request.getLines().forEach(line -> createOrderLine(salesOrderFactory, line));

		final I_C_Order salesOrderRecord = salesOrderFactory.createAndComplete();

		return toSalesOrder(salesOrderRecord, request.getDocTypeName());
	}

	private void createOrderLine(final OrderFactory salesOrderFactory, final JsonSalesOrderLine salesOrderLine)
	{
		final IProductBL productBL = Services.get(IProductBL.class);

		// Try to find product by GTIN first
		ProductId productId = null;
		try
		{
			final GTIN gtin = GTIN.ofString(salesOrderLine.getGtinCode());
			productId = productBL.getProductIdByGTIN(gtin, Env.getClientId()).orElse(null);
		}
		catch (Exception e)
		{
			// If GTIN parsing fails, try to find by barcode as fallback
			productId = productBL.getProductIdByBarcode(salesOrderLine.getGtinCode(), Env.getClientId()).orElse(null);
		}
		if (productId == null)
		{
			throw new AdempiereException("@NotFound@ M_Product_ID@ (@GTIN=" + salesOrderLine.getGtinCode() + ")");
		}

		final I_C_UOM uom = productBL.getStockUOM(productId);
		final Quantity qty = Quantity.of(salesOrderLine.getQty(), uom);

		salesOrderFactory.newOrderLine()
				.productId(productId)
				.addQty(qty)
				.manualPrice(salesOrderLine.getPrice())
				.description(salesOrderLine.getDescription());
	}

	private JsonSalesOrder toSalesOrder(final I_C_Order salesOrderRecord, final String docTypeName)
	{
		final IOrderDAO orderDAO = Services.get(IOrderDAO.class);
		final List<I_C_OrderLine> orderLines = orderDAO.retrieveOrderLines(salesOrderRecord);
		
		// Calculate totals
		BigDecimal totalAmount = BigDecimal.ZERO;
		BigDecimal totalQuantity = BigDecimal.ZERO;
		final List<JsonSalesOrderLineDetail> lineDetails = orderLines.stream()
				.map(this::toSalesOrderLineDetail)
				.collect(ImmutableList.toImmutableList());
		
		// Calculate totals from lines
		for (final JsonSalesOrderLineDetail lineDetail : lineDetails)
		{
			if (lineDetail.getLineAmount() != null)
			{
				totalAmount = totalAmount.add(lineDetail.getLineAmount());
			}
			totalQuantity = totalQuantity.add(lineDetail.getQuantity());
		}
		
		// Calculate expiry date (24 hours from now) and format it in a readable way
		final ZonedDateTime expiryDateTime = ZonedDateTime.now(ZoneId.systemDefault()).plusHours(24);
		final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
		final String formattedExpiryDate = expiryDateTime.format(formatter);
		
		return JsonSalesOrder.builder()
				.salesOrderId(String.valueOf(salesOrderRecord.getC_Order_ID()))
				.documentNo(salesOrderRecord.getDocumentNo())
				.totalAmount(totalAmount)
				.totalQuantity(totalQuantity)
				.orderLines(lineDetails)
				.expiryDate(expiryDateTime)
				.formattedExpiryDate(formattedExpiryDate)
				.docTypeName(docTypeName)
				.build();
	}
	
	private JsonSalesOrderLineDetail toSalesOrderLineDetail(final I_C_OrderLine orderLine)
	{
		final IProductDAO productsRepo = Services.get(IProductDAO.class);
		final ProductId productId = ProductId.ofRepoId(orderLine.getM_Product_ID());
		final String productCode = productsRepo.retrieveProductValueByProductId(productId);
		
		// Get the GTIN code from the product
		String gtinCode = null;
		try
		{
			// Try to get the GTIN from the product
			final I_M_Product product = productsRepo.getById(productId);
			if (product != null && product.getGTIN() != null && !product.getGTIN().trim().isEmpty())
			{
				gtinCode = product.getGTIN();
			}
		}
		catch (Exception e)
		{
			// If we can't get the GTIN, leave it as null
			logger.debug("Could not retrieve GTIN for product {}, error: {}", productId, e.getMessage());
		}
		
		final BigDecimal quantity = orderLine.getQtyOrdered();
		final BigDecimal unitPrice = orderLine.getPriceActual();
		final BigDecimal lineAmount = quantity.multiply(unitPrice);
		
		return JsonSalesOrderLineDetail.builder()
				.productCode(productCode)
				.gtinCode(gtinCode)
				.description(orderLine.getDescription())
				.quantity(quantity)
				.lineAmount(lineAmount)
				.unitPrice(unitPrice)
				.build();
	}

	private JsonSalesOrderAttachment toSalesOrderAttachment(final int salesOrderId, final AttachmentEntry entry)
	{
		return JsonSalesOrderAttachment.builder()
				.salesOrderId(String.valueOf(salesOrderId))
				.id(AttachmentEntryId.getRepoId(entry.getId()))
				.type(JsonConverters.toJsonAttachmentSourceType(entry.getType()))
				.filename(entry.getFilename())
				.mimeType(entry.getMimeType())
				.url(entry.getUrl() != null ? entry.getUrl().toString() : null)
				.build();
	}

}
