package com.acme.ecommerce.controller;

import com.acme.ecommerce.domain.*;
import com.acme.ecommerce.service.ProductService;
import com.acme.ecommerce.service.PurchaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import javax.servlet.http.HttpSession;
import java.math.BigDecimal;

@Controller
@RequestMapping("/cart")
@Scope("request")
public class CartController {
	final Logger logger = LoggerFactory.getLogger(CartController.class);

	@Autowired
	PurchaseService purchaseService;

	@Autowired
	private ProductService productService;

	@Autowired
	private ShoppingCart sCart;

	@Autowired
	private HttpSession session;

	@RequestMapping("")
	public String viewCart(Model model) {
		logger.debug("Getting Product List");
		logger.debug("Session ID = " + session.getId());

		Purchase purchase = sCart.getPurchase();
		BigDecimal subTotal = new BigDecimal(0);

		model.addAttribute("purchase", purchase);
		if (purchase != null) {
			for (ProductPurchase pp : purchase.getProductPurchases()) {
				logger.debug("cart has " + pp.getQuantity() + " of " + pp.getProduct().getName());
				subTotal = subTotal.add(pp.getProduct().getPrice().multiply(new BigDecimal(pp.getQuantity())));
			}

			if (!subTotal.equals(new BigDecimal(0))) {
				model.addAttribute("subTotal", subTotal);
			}

		} else {
			logger.error("No purchases Found for session ID=" + session.getId());
			return "redirect:/error";
		}
		return "cart";
	}

	@RequestMapping(path="/add", method = RequestMethod.POST)
	public RedirectView addToCart(@ModelAttribute(value="productId") long productId,
								  @ModelAttribute(value="quantity") int quantity,
								  RedirectAttributes redirectAttributes) {
		boolean productAlreadyInCart = false;
		RedirectView redirect = new RedirectView("/product/");
		redirect.setExposeModelAttributes(false);

		Product addProduct = productService.findById(productId);
		if (addProduct != null) {
			int stockQuantity = addProduct.getQuantity();
			if (stockQuantity >= quantity) {
				logger.debug("Adding Product: " + addProduct.getId());

				Purchase purchase = sCart.getPurchase();
				if (purchase == null) {
					purchase = new Purchase();
					sCart.setPurchase(purchase);
				} else {
					for (ProductPurchase pp : purchase.getProductPurchases()) {
						if (pp.getProduct() != null) {
							if (pp.getProduct().getId().equals(productId)) {
								pp.setQuantity(pp.getQuantity() + quantity);

								// Update stock quantity
								addProduct.setQuantity(stockQuantity - quantity);
								productService.save(addProduct);

								productAlreadyInCart = true;
								break;
							}
						}
					}
				}
				if (!productAlreadyInCart) {
					ProductPurchase newProductPurchase = new ProductPurchase();
					newProductPurchase.setProduct(addProduct);
					newProductPurchase.setQuantity(quantity);

					// Update stock quantity
					addProduct.setQuantity(stockQuantity - quantity);
					productService.save(addProduct);

					newProductPurchase.setPurchase(purchase);
					purchase.getProductPurchases().add(newProductPurchase);
				}
				logger.debug("Added " + quantity + " of " + addProduct.getName() + " to cart");
				sCart.setPurchase(purchaseService.save(purchase));

                redirectAttributes.addFlashAttribute("flash",
                        new FlashMessage("Product added to cart", FlashMessage.Status.SUCCESS));
			} else if(stockQuantity < quantity && addProduct != null) {
				logger.error("Attempt to add higher quantity of product than available: " + productId);
				redirectAttributes.addFlashAttribute("error", "quantity");
				redirectAttributes.addFlashAttribute("flash",
						new FlashMessage("Trying to add higher quantity than available", FlashMessage.Status.FAILED));
//				redirect.setUrl("/cart");
			}
		} else {
			logger.error("Attempt to add unknown product: " + productId);
			redirect.setUrl("/error");
		}

		return redirect;
	}

	@RequestMapping(path="/update", method = RequestMethod.POST)
	public RedirectView updateCart(@ModelAttribute(value="productId") long productId,
								   @ModelAttribute(value="newQuantity") int newQuantity,
								   RedirectAttributes redirectAttributes) {
		logger.debug("Updating Product: " + productId + " with Quantity: " + newQuantity);
		RedirectView redirect = new RedirectView("/cart");
		redirect.setExposeModelAttributes(false);

		Product updateProduct = productService.findById(productId);
		if (updateProduct != null) {
			int stockQuantity = updateProduct.getQuantity();
			Purchase purchase = sCart.getPurchase();
			if (purchase == null) {
				logger.error("Unable to find shopping cart for update");
				redirect.setUrl("/error");
			} else {
				for (ProductPurchase pp : purchase.getProductPurchases()) {
					if (pp.getProduct() != null) {
						if (pp.getProduct().getId().equals(productId)) {
							int oldQuantity = pp.getQuantity();
							if (newQuantity > 0  && stockQuantity + pp.getQuantity() >= newQuantity) {

								pp.setQuantity(newQuantity);

								// Update stock quantity accordingly
								if (newQuantity == oldQuantity) {
									logger.debug("Quantity of product " + updateProduct.getName() + " stayed the same");
									return redirect;
								} else if (newQuantity > oldQuantity) {
									updateProduct.setQuantity((stockQuantity + oldQuantity) - newQuantity);
								} else if (newQuantity < oldQuantity) {
									updateProduct.setQuantity(stockQuantity + (oldQuantity - newQuantity));
								}

								logger.debug("Updated " + updateProduct.getName() + " to " + newQuantity);
							}  else if (stockQuantity < newQuantity) {
								logger.error("Attempt to update to a higher quantity than available");
								redirectAttributes.addFlashAttribute("error", "quantity");
								redirect.setUrl("/cart");
							}  else {
								purchase.getProductPurchases().remove(pp);

								updateProduct.setQuantity(stockQuantity + oldQuantity);

								logger.debug("Removed " + updateProduct.getName() + " because quantity was set to " + newQuantity);
							}
							// Save updated stock quantity
							productService.save(updateProduct);
							break;
						}
					}
				}
			}
			sCart.setPurchase(purchaseService.save(purchase));
		} else {
			logger.error("Attempt to update on non-existent product");
			redirect.setUrl("/error");
		}

		return redirect;
	}

	@RequestMapping(path="/remove", method = RequestMethod.POST)
	public RedirectView removeFromCart(@ModelAttribute(value="productId") long productId) {
		logger.debug("Removing Product: " + productId);
		RedirectView redirect = new RedirectView("/cart");
		redirect.setExposeModelAttributes(false);

		Product updateProduct = productService.findById(productId);
		if (updateProduct != null) {
			int stockQuantity = updateProduct.getQuantity();
			Purchase purchase = sCart.getPurchase();
			if (purchase != null) {
				for (ProductPurchase pp : purchase.getProductPurchases()) {
					if (pp.getProduct() != null) {
						if (pp.getProduct().getId().equals(productId)) {
							int purchaseQuantity = pp.getQuantity();
							purchase.getProductPurchases().remove(pp);

							// Update stock quantity
							Product product = pp.getProduct();
							product.setQuantity(stockQuantity + purchaseQuantity);
							productService.save(product);

							logger.debug("Removed " + updateProduct.getName());
							break;
						}
					}
				}
				purchase = purchaseService.save(purchase);
				sCart.setPurchase(purchase);
				if (purchase.getProductPurchases().isEmpty()) {
					//if last item in cart redirect to product else return cart
					redirect.setUrl("/product/");
				}
			} else {
				logger.error("Unable to find shopping cart for update");
				redirect.setUrl("/error");
			}
		} else {
			logger.error("Attempt to update on non-existent product");
			redirect.setUrl("/error");
		}

		return redirect;
	}

	@RequestMapping(path="/empty", method = RequestMethod.POST)
	public RedirectView emptyCart() {
		RedirectView redirect = new RedirectView("/product/");
		redirect.setExposeModelAttributes(false);

		logger.debug("Emptying Cart");
		Purchase purchase = sCart.getPurchase();
		if (purchase != null) {
			// Update stock quantities
			for (ProductPurchase pp : purchase.getProductPurchases()) {
				Product product = pp.getProduct();
				int stockQuantity = product.getQuantity();

				product.setQuantity(stockQuantity + pp.getQuantity());
				productService.save(product);
			}
			purchase.getProductPurchases().clear();
			sCart.setPurchase(purchaseService.save(purchase));
		} else {
			logger.error("Unable to find shopping cart for update");
			redirect.setUrl("/error");
		}

		return redirect;
	}
}
